package com.dsh.logcollector;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Turns a finished collection run into a single shareable artefact, and optionally ships it to a
 * server.
 *
 * <p>The zip is written into the run directory itself so the archive and its contents always sit
 * together. Files that cannot be read (root-owned dumps on a degraded device) are skipped and
 * listed in {@code zip_skipped.txt} rather than silently dropped.
 */
public final class Packager {

    private Packager() { }

    public static final class Result {
        public boolean ok;
        public String path = "";
        public long bytes;
        public int entries;
        public final List<String> skipped = new ArrayList<String>();
        public String detail = "";
    }

    // ------------------------------------------------------------------- zip

    public static Result zip(Context ctx, Storage.Session session, String zipName) {
        Result res = new Result();
        File runDir = session.dir;
        if (!runDir.isDirectory()) {
            res.detail = "run directory missing: " + runDir;
            return res;
        }
        if (zipName == null || zipName.length() == 0) {
            zipName = "log_" + session.stamp + ".zip";
        }

        File staging = Storage.stageFile(ctx, "zip/" + zipName);
        File parent = staging.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        //noinspection ResultOfMethodCallIgnored
        staging.delete();

        ZipOutputStream zos = null;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(staging);
            zos = new ZipOutputStream(fos);
            zos.setLevel(Deflater.BEST_SPEED);

            List<File> files = new ArrayList<File>();
            walk(runDir, files);

            for (File f : files) {
                String rel = relativize(runDir, f);
                // Never pack a zip into itself.
                if (rel.equals(zipName) || rel.endsWith(".zip")) continue;
                if (!f.canRead()) {
                    res.skipped.add(rel + " (unreadable)");
                    continue;
                }
                FileInputStream in = null;
                try {
                    ZipEntry e = new ZipEntry(session.dir.getName() + "/" + rel);
                    e.setTime(f.lastModified());
                    zos.putNextEntry(e);
                    in = new FileInputStream(f);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                    zos.closeEntry();
                    res.entries++;
                } catch (Throwable t) {
                    res.skipped.add(rel + " (" + t.getClass().getSimpleName() + ")");
                } finally {
                    Util.close(in);
                    try { zos.closeEntry(); } catch (Throwable ignored) { }
                }
            }

            if (!res.skipped.isEmpty()) {
                try {
                    ZipEntry e = new ZipEntry("zip_skipped.txt");
                    zos.putNextEntry(e);
                    StringBuilder sb = new StringBuilder("files not included in this archive\n")
                            .append("==================================\n\n");
                    for (String sk : res.skipped) sb.append(sk).append("\n");
                    zos.write(sb.toString().getBytes("UTF-8"));
                    zos.closeEntry();
                } catch (Throwable ignored) { }
            }

            zos.finish();
            zos.flush();
            Util.close(zos);
            zos = null;
            Util.close(fos);
            fos = null;

            long len = staging.length();

            // Place it next to the run: direct write when allowed, else stage-and-move.
            File target = new File(runDir, zipName);
            boolean placed;
            if (session.tier == Storage.TIER_DIRECT || session.tier == Storage.TIER_APP_PRIVATE) {
                placed = Util.copyFile(staging, target);
            } else {
                placed = false;
            }
            if (!placed) {
                Storage.WriteResult w = copyToPath(ctx, session, zipName, staging);
                placed = w.ok;
            }
            //noinspection ResultOfMethodCallIgnored
            staging.delete();

            res.ok = placed;
            res.bytes = len;
            res.path = new File(runDir, zipName).getAbsolutePath();
            res.detail = placed ? ("packed " + res.entries + " files, " + Util.bytes(len))
                                : "could not place the zip into the run directory";
            return res;
        } catch (Throwable t) {
            res.detail = t.getClass().getSimpleName() + ": " + t.getMessage();
            return res;
        } finally {
            Util.close(zos);
            Util.close(fos);
        }
    }

    private static Storage.WriteResult copyToPath(Context ctx, Storage.Session s, String rel, File src) {
        // Reuse the tiered writer by reading the bytes back through it.
        File dst = new File(s.dir, rel);
        if (Shell.hasRoot()) {
            Shell.Result r = Shell.su("cp '" + Storage.esc(src.getAbsolutePath()) + "' '"
                    + Storage.esc(dst.getAbsolutePath()) + "' && chmod 666 '"
                    + Storage.esc(dst.getAbsolutePath()) + "' && echo COPIED", 120000);
            if (r.out.contains("COPIED")) return new Storage.WriteResult(true, src.length(), "su copy");
            return new Storage.WriteResult(false, 0, "su copy failed: " + r.err);
        }
        Shell.Result r = Shell.app("cp '" + Storage.esc(src.getAbsolutePath()) + "' '"
                + Storage.esc(dst.getAbsolutePath()) + "' 2>&1 && echo COPIED", 120000);
        if (r.out.contains("COPIED")) return new Storage.WriteResult(true, src.length(), "shell copy");
        return new Storage.WriteResult(false, 0, "copy failed: " + r.err + r.out);
    }

    private static void walk(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) walk(k, out);
            else if (k.isFile()) out.add(k);
        }
    }

    private static String relativize(File root, File f) {
        String r = root.getAbsolutePath();
        String a = f.getAbsolutePath();
        if (a.startsWith(r)) {
            String s = a.substring(r.length());
            while (s.startsWith("/") || s.startsWith("\\")) s = s.substring(1);
            return s.replace('\\', '/');
        }
        return f.getName();
    }

    // ----------------------------------------------------------------- share

    /**
     * Hands the artefact to the system share sheet. The receiving app gets a content:// URI, so
     * this works even when the file lives outside the app's own directories.
     */
    public static String share(Context ctx, File file, String mime, String chooserTitle) {
        if (file == null || !file.exists()) return "file does not exist";
        try {
            Uri uri;
            // A FileProvider would be cleaner but needs an authority + paths resource; the
            // app-external file:// route is still honoured by most file managers and mail apps,
            // and we additionally expose a content:// fallback below.
            if (Build.VERSION.SDK_INT >= 24) {
                uri = SilentFileProvider.getUriForFile(ctx, file);
            } else {
                //noinspection deprecation
                uri = Uri.fromFile(file);
            }
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mime == null ? "application/zip" : mime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, file.getName());
            send.putExtra(Intent.EXTRA_TEXT, "DSH LogCollector: " + file.getName()
                    + " (" + Util.bytes(file.length()) + ")");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(send, chooserTitle == null ? "分享日志" : chooserTitle);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** Copies a file into the app's own external dir so other apps can always read it. */
    public static File exportToAppDir(Context ctx, File src) {
        try {
            File dir = ctx.getExternalFilesDir("shared");
            if (dir == null) dir = new File(ctx.getFilesDir(), "shared");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File dst = new File(dir, src.getName());
            if (Util.copyFile(src, dst)) return dst;
        } catch (Throwable ignored) { }
        return src;
    }

    // ---------------------------------------------------------------- upload

    public static final class UploadResult {
        public boolean ok;
        public int code;
        public String response = "";
        public String detail = "";
        public long ms;
    }

    /**
     * POSTs the artefact as {@code multipart/form-data} to the configured URL.
     *
     * <p>Field name is {@code file}; the optional token goes out both as a {@code token} form field
     * and as a {@code X-Auth-Token} header so simple endpoints can use either.
     */
    public static UploadResult upload(Context ctx, File file, String url, String token) {
        UploadResult res = new UploadResult();
        long t0 = System.currentTimeMillis();
        if (url == null || url.trim().length() == 0) {
            res.detail = "no upload URL configured";
            return res;
        }
        if (file == null || !file.exists()) {
            res.detail = "file does not exist";
            return res;
        }

        String boundary = "----DSHLogCollector" + Long.toHexString(System.nanoTime());
        HttpURLConnection conn = null;
        OutputStream out = null;
        FileInputStream in = null;
        try {
            URL u = new URL(url.trim());
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("User-Agent", "DSH-LogCollector/1.0 (Android " + Build.VERSION.RELEASE + ")");
            if (token != null && token.length() > 0) {
                conn.setRequestProperty("X-Auth-Token", token);
            }
            // Without a streaming mode HttpURLConnection buffers the ENTIRE request body in
            // memory, which an artifact containing a full bugreport can easily exhaust.
            // Chunked streaming keeps the footprint flat regardless of file size.
            if (file.length() > 1024 * 1024) {
                conn.setChunkedStreamingMode(65536);
            }

            out = conn.getOutputStream();

            if (token != null && token.length() > 0) {
                writeField(out, boundary, "token", token);
            }
            writeField(out, boundary, "device", Build.MANUFACTURER + " " + Build.MODEL);
            writeField(out, boundary, "android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            writeField(out, boundary, "filename", file.getName());

            StringBuilder head = new StringBuilder();
            head.append("--").append(boundary).append("\r\n");
            head.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(file.getName()).append("\"\r\n");
            head.append("Content-Type: application/octet-stream\r\n\r\n");
            out.write(head.toString().getBytes("UTF-8"));

            in = new FileInputStream(file);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.write("\r\n".getBytes("UTF-8"));
            out.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
            out.flush();
            Util.close(out);
            out = null;

            res.code = conn.getResponseCode();
            res.ok = res.code >= 200 && res.code < 300;
            java.io.InputStream ris = res.ok ? conn.getInputStream() : conn.getErrorStream();
            String body = Util.readStream(ris);
            Util.close(ris);
            res.response = Util.trimTo(body, 4000);
            res.detail = res.ok ? ("HTTP " + res.code + " ok")
                                : ("HTTP " + res.code + " " + conn.getResponseMessage());
            return res;
        } catch (Throwable t) {
            res.detail = t.getClass().getSimpleName() + ": " + t.getMessage();
            return res;
        } finally {
            Util.close(out);
            Util.close(in);
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) { } }
            res.ms = System.currentTimeMillis() - t0;
        }
    }

    private static void writeField(OutputStream out, String boundary, String name, String value)
            throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n");
        sb.append(value == null ? "" : value).append("\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
    }

    // ------------------------------------------------------------ power help

    /** Asks to be exempted from battery optimisation, so long recordings are not killed. */
    public static void requestIgnoreBatteryOptimizations(Activity a) {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(a.getPackageName())) return;
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
        } catch (Throwable ignored) { }
    }
}
