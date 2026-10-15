package com.dsh.logcollector;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small helpers shared across the app. Java 7/8 only - this is compiled with --release 8. */
public final class Util {

    private Util() { }

    // ------------------------------------------------------------------ time

    private static final String STAMP = "yyyyMMdd-HHmmss";
    private static final String STAMP_MS = "yyyyMMdd-HHmmss.SSS";
    private static final String HUMAN = "yyyy-MM-dd HH:mm:ss";

    public static String stamp() { return new SimpleDateFormat(STAMP, Locale.US).format(new Date()); }
    public static String stampMs() { return new SimpleDateFormat(STAMP_MS, Locale.US).format(new Date()); }
    public static String human() { return new SimpleDateFormat(HUMAN, Locale.US).format(new Date()); }
    public static String human(long ms) { return new SimpleDateFormat(HUMAN, Locale.US).format(new Date(ms)); }

    // ------------------------------------------------------------------ bytes

    public static String bytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", n / (1024.0 * 1024 * 1024));
    }

    public static long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += dirSize(k);
        return total;
    }

    public static int countFiles(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return 1;
        int n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += countFiles(k);
        return n;
    }

    // -------------------------------------------------------------------- io

    public static String readFile(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            return readStream(in);
        } catch (Throwable t) {
            return null;
        } finally {
            close(in);
        }
    }

    public static String readStream(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    public static boolean writeFile(File f, String text) {
        FileOutputStream out = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            close(out);
        }
    }

    public static boolean writeBytes(File f, byte[] data) {
        FileOutputStream out = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            out = new FileOutputStream(f);
            out.write(data);
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            close(out);
        }
    }

    public static boolean copyFile(File src, File dst) {
        InputStream in = null;
        OutputStream out = null;
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            in = new BufferedInputStream(new FileInputStream(src), 65536);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            close(in);
            close(out);
        }
    }

    public static void close(Object c) {
        try {
            if (c instanceof java.io.Closeable) ((java.io.Closeable) c).close();
        } catch (Throwable ignored) { }
    }

    public static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** A filesystem-safe slug for use inside a filename. */
    public static String slug(String s) {
        if (s == null) return "unknown";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    public static String join(String sep, java.util.List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    public static String trimTo(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n... [truncated]";
    }
}
