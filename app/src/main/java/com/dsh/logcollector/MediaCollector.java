package com.dsh.logcollector;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Screenshot and screen-recording capture.
 *
 * <p>Three routes are tried in order, because which one works depends entirely on the device:
 * <ol>
 *   <li>{@code screencap} / {@code screenrecord} as root, writing straight to the target dir.</li>
 *   <li>the same binaries as a shell uid, writing to a staged path we then copy.</li>
 *   <li>{@link MediaProjection}, which is the only route that works for a plain unprivileged app -
 *       it needs one user tap and is therefore opt-in from the UI.</li>
 * </ol>
 */
public final class MediaCollector {

    private MediaCollector() { }

    /** Holds the MediaProjection consent result across the activity/collector boundary. */
    public static final class ProjectionGrant {
        public static volatile ProjectionGrant INSTANCE;
        public final int resultCode;
        public final Intent data;
        public ProjectionGrant(int resultCode, Intent data) {
            this.resultCode = resultCode;
            this.data = data;
        }
        public boolean valid() { return resultCode == Activity.RESULT_OK && data != null; }
    }

    // ------------------------------------------------------------- screenshot

    public static void screenshot(Context ctx, Storage.Session s, StringBuilder index, List<String> warnings) {
        // ---- route 1: root screencap straight into place -------------------
        if (Shell.hasRoot()) {
            String dst = s.dir.getAbsolutePath() + "/media/screenshot.png";
            Storage.mkdirs(ctx, s, "media");
            Shell.Result r = Shell.su(
                    "screencap -p '" + Storage.esc(dst) + "' && chmod 666 '" + Storage.esc(dst)
                    + "' && echo SHOT_OK", 40000);
            if (r.out.contains("SHOT_OK")) {
                index.append("media/screenshot.png :: ok (root screencap)\n");
                return;
            }
            index.append("media/screenshot.png :: root screencap failed ("
                    + firstLine(r.err) + "), trying other routes\n");
        }

        // ---- route 2: stage it locally, then move it -----------------------
        File stage = Storage.stageFile(ctx, "media/screenshot.png");
        File parent = stage.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        //noinspection ResultOfMethodCallIgnored
        stage.delete();

        Shell.Result r2 = Shell.runReported(
                "screencap -p '" + Storage.esc(stage.getAbsolutePath()) + "' 2>&1 && echo SHOT_STAGED", 40000);
        if (stage.exists() && stage.length() > 0) {
            Storage.WriteResult w = Storage.copyInto(ctx, s, "media/screenshot.png", stage);
            index.append("media/screenshot.png :: ").append(w.ok ? "ok (" + r2.modeName() + " screencap, staged)"
                    : ("FAILED: " + w.detail)).append("\n");
            //noinspection ResultOfMethodCallIgnored
            stage.delete();
            if (w.ok) return;
        }

        // ---- route 3: MediaProjection (needs a one-time user consent) ------
        ProjectionGrant grant = ProjectionGrant.INSTANCE;
        if (grant != null && grant.valid()) {
            String err = captureViaProjection(ctx, s);
            if (err == null) {
                index.append("media/screenshot.png :: ok (MediaProjection)\n");
                return;
            }
            index.append("media/screenshot.png :: MediaProjection failed: ").append(err).append("\n");
            warnings.add("MediaProjection 截屏失败：" + err);
        } else {
            index.append("media/screenshot.png :: NOT CAPTURED (screencap denied, "
                    + "no MediaProjection consent)\n");
            warnings.add("截屏失败：非 root 设备需要在界面里点一次「授权截屏」按钮。");
        }
    }

    /** Returns null on success, or a short error string. */
    private static String captureViaProjection(Context ctx, Storage.Session s) {
        if (Build.VERSION.SDK_INT < 21) return "API < 21";
        MediaProjection mp = null;
        VirtualDisplay vd = null;
        ImageReader reader = null;
        try {
            ProjectionGrant grant = ProjectionGrant.INSTANCE;
            if (grant == null || !grant.valid()) return "no consent";

            // Android 14 requires a foreground service of type mediaProjection to be running.
            // Ask the service to promote itself rather than just failing.
            if (Build.VERSION.SDK_INT >= 34) {
                if (!CollectorService.isProjectionServiceRunning()) {
                    if (!CollectorService.ensureProjectionForeground()) {
                        return "on Android 14+ a foreground service of type mediaProjection must be "
                             + "running first; start the collector service and retry";
                    }
                    // Give the platform a moment to register the new type.
                    try { Thread.sleep(400); } catch (InterruptedException ignored) { }
                }
            }

            MediaProjectionManager mpm =
                    (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) return "no MediaProjectionManager";
            mp = mpm.getMediaProjection(grant.resultCode, grant.data);
            if (mp == null) return "getMediaProjection returned null";

            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            if (wm != null) {
                //noinspection deprecation
                wm.getDefaultDisplay().getRealMetrics(dm);
            } else {
                dm.widthPixels = 1080; dm.heightPixels = 1920; dm.densityDpi = 320;
            }
            int w = Math.max(1, dm.widthPixels);
            int h = Math.max(1, dm.heightPixels);
            int dpi = dm.densityDpi;

            final Object lock = new Object();
            final boolean[] done = { false };
            final Bitmap[] shot = { null };

            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                        if (img == null) return;
                        Image.Plane[] planes = img.getPlanes();
                        if (planes.length == 0) return;
                        ByteBuffer buf = planes[0].getBuffer();
                        int pixelStride = planes[0].getPixelStride();
                        int rowStride = planes[0].getRowStride();
                        int rowPadding = rowStride - pixelStride * img.getWidth();
                        Bitmap bmp = Bitmap.createBitmap(
                                img.getWidth() + rowPadding / pixelStride, img.getHeight(),
                                Bitmap.Config.ARGB_8888);
                        bmp.copyPixelsFromBuffer(buf);
                        Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, img.getWidth(), img.getHeight());
                        if (cropped != bmp) bmp.recycle();
                        shot[0] = cropped;
                    } catch (Throwable ignored) {
                    } finally {
                        if (img != null) { try { img.close(); } catch (Throwable ignored) { } }
                        synchronized (lock) { done[0] = true; lock.notifyAll(); }
                    }
                }
            }, new Handler(Looper.getMainLooper()));

            vd = mp.createVirtualDisplay("dsh-shot", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
            if (vd == null) return "createVirtualDisplay returned null";

            synchronized (lock) {
                long deadline = System.currentTimeMillis() + 8000;
                while (!done[0] && System.currentTimeMillis() < deadline) {
                    try { lock.wait(400); } catch (InterruptedException ie) { break; }
                }
            }

            Bitmap bmp = shot[0];
            if (bmp == null) return "no frame produced within 8s";

            File out = new File(s.dir, "media/screenshot.png");
            File p = out.getParentFile();
            if (p != null && !p.exists()) {
                //noinspection ResultOfMethodCallIgnored
                p.mkdirs();
            }
            boolean wrote = false;
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(out);
                wrote = bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
            } finally {
                Util.close(fos);
                bmp.recycle();
            }

            if (!wrote) {
                // Target dir may not be app-writable: stage then move.
                File st = Storage.stageFile(ctx, "media/screenshot.png");
                FileOutputStream fos2 = null;
                try {
                    fos2 = new FileOutputStream(st);
                    Bitmap b2 = android.graphics.BitmapFactory.decodeFile(out.getAbsolutePath());
                    if (b2 != null) {
                        b2.compress(Bitmap.CompressFormat.PNG, 100, fos2);
                        b2.recycle();
                    }
                    fos2.flush();
                } finally {
                    Util.close(fos2);
                }
                Storage.WriteResult w2 = Storage.copyInto(ctx, s, "media/screenshot.png", st);
                //noinspection ResultOfMethodCallIgnored
                st.delete();
                if (!w2.ok) return "could not place screenshot: " + w2.detail;
            }
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (vd != null) { try { vd.release(); } catch (Throwable ignored) { } }
            if (reader != null) { try { reader.close(); } catch (Throwable ignored) { } }
            if (mp != null) { try { mp.stop(); } catch (Throwable ignored) { } }
        }
    }

    // --------------------------------------------------------- screen record

    public static void screenRecord(Context ctx, Storage.Session s, StringBuilder index,
                                    List<String> warnings, int seconds, Collector.Progress cb) {
        if (seconds <= 0) seconds = 15;
        final int limit = Math.min(seconds, 180);   // screenrecord's own hard cap is 180 s

        Shell.Result avail = Shell.runReported("command -v screenrecord || ls /system/bin/screenrecord 2>&1", 12000);
        boolean present = avail.out.contains("screenrecord");
        if (!present) {
            index.append("media/screenrecord.mp4 :: screenrecord binary not present\n");
            warnings.add("设备上没有 screenrecord，跳过录屏。");
            return;
        }

        Storage.mkdirs(ctx, s, "media");
        File stage = Storage.stageFile(ctx, "media/screenrecord.mp4");
        File p = stage.getParentFile();
        if (p != null && !p.exists()) {
            //noinspection ResultOfMethodCallIgnored
            p.mkdirs();
        }
        //noinspection ResultOfMethodCallIgnored
        stage.delete();

        // `--time-limit` avoids ever needing to kill the process, which would corrupt the file.
        String cmd = "screenrecord --time-limit " + limit
                + " --bit-rate 8000000 --verbose '" + Storage.esc(stage.getAbsolutePath()) + "'";
        if (cb != null) cb.onStep("录屏", "录制 " + limit + " 秒…");

        Shell.Result r = Shell.runReported(cmd, (limit + 20) * 1000L);

        if (stage.exists() && stage.length() > 0) {
            Storage.WriteResult w = Storage.copyInto(ctx, s, "media/screenrecord.mp4", stage);
            index.append("media/screenrecord.mp4 :: ")
                 .append(w.ok ? ("ok (" + limit + "s, " + Util.bytes(stage.length()) + ", " + r.modeName() + ")")
                              : ("FAILED: " + w.detail))
                 .append("\n");
            if (r.out.length() > 0) {
                Storage.writeText(ctx, s, "media/screenrecord.log",
                        "$ " + cmd + "\n[mode=" + r.modeName() + " exit=" + r.code + "]\n\n" + r.text());
            }
            //noinspection ResultOfMethodCallIgnored
            stage.delete();
        } else {
            index.append("media/screenrecord.mp4 :: FAILED (")
                 .append(r.modeName()).append(" exit=").append(r.code).append(")")
                 .append(r.err.length() > 0 ? " :: " + firstLine(r.err) : "").append("\n");
            warnings.add("录屏失败（" + r.modeName() + " exit=" + r.code
                    + "）。非 root 设备通常需要先 adb shell 授权或被 SELinux 限制。");
            Storage.writeText(ctx, s, "media/screenrecord_error.txt",
                    "$ " + cmd + "\n\n[mode=" + r.modeName() + " exit=" + r.code + "]\n\n"
                    + "--- stdout ---\n" + r.out + "\n\n--- stderr ---\n" + r.err);
        }
    }

    private static String firstLine(String s) {
        if (s == null || s.length() == 0) return "";
        int i = s.indexOf('\n');
        String l = i < 0 ? s : s.substring(0, i);
        return l.length() > 200 ? l.substring(0, 200) : l.trim();
    }
}
