package com.dsh.logcollector;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Rolling "black box" log recorder.
 *
 * <p>A single long-lived {@code logcat} child process writes into a rotating file set inside the
 * app's cache directory. Because logcat does the rotation itself ({@code -r} / {@code -n}), the
 * recorder costs almost nothing: no polling, no parsing, no heap growth, and it survives the app
 * being backgrounded as long as the foreground service runs.
 *
 * <p>When something interesting happens (crash, ANR, or the user tapping "dump"), {@link #extract}
 * reads the rotated files newest-first, filters to the last N minutes, and hands back a single
 * text blob that can be dropped straight into a collection run.
 */
public final class Blackbox {

    private Blackbox() { }

    private static final String DIR_NAME = "blackbox";
    private static final String BASE_NAME = "blackbox";
    /** Bytes per rotated file. logcat's -r takes KB. */
    private static final int ROTATE_KB = 2048;

    private static Process sProcess;
    private static Thread  sWaiter;
    private static volatile boolean sRunning;
    private static volatile long sStartedAt;
    private static volatile String sLastError = "";
    private static volatile int sFileCount = 4;

    public static boolean isRunning() { return sRunning; }
    public static long startedAt() { return sStartedAt; }
    public static String lastError() { return sLastError; }

    public static File dir(Context ctx) {
        File d = new File(ctx.getCacheDir(), DIR_NAME);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Starts the recorder if it is not already running. Safe to call repeatedly, and safe to call
     * from the main thread: the buffer probe it needs is moved onto a worker thread when the
     * caller is the UI thread.
     */
    public static synchronized String start(final Context ctx, final int totalBufferKb, final int minutes) {
        if (sRunning && sProcess != null) return "already running";
        if (sStarting) return "starting";

        boolean onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
        if (onMain && sBufferArgsResolved == null) {
            // Never run shell probes on the UI thread; do the whole start in the background.
            sStarting = true;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try { startInternal(ctx, totalBufferKb, minutes); }
                    catch (Throwable t2) { sLastError = String.valueOf(t2); }
                    finally { sStarting = false; }
                }
            }, "blackbox-start");
            t.setDaemon(true);
            t.start();
            return "starting in background";
        }
        return startInternal(ctx, totalBufferKb, minutes);
    }

    private static volatile boolean sStarting;
    private static volatile String[] sBufferArgsResolved;

    private static String startInternal(Context ctx, int totalBufferKb, int minutes) {
        File d = dir(ctx);
        // Start from a clean set so timestamps in the extracted blob are unambiguous.
        File[] old = d.listFiles();
        if (old != null) for (File f : old) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }

        int total = Math.max(512, totalBufferKb);
        int count = Math.max(2, Math.min(20, (int) Math.ceil(total / (double) ROTATE_KB)));
        sFileCount = count;

        File out = new File(d, BASE_NAME + ".txt");

        // Resolve the buffer set once. This is a background thread by the time we get here,
        // so the blocking probe is fine.
        String[] buffers = sBufferArgsResolved;
        if (buffers == null) {
            try {
                buffers = Logcat.probeBlocking();
            } catch (Throwable t) {
                buffers = Logcat.DEFAULT_BUFFERS;
            }
            sBufferArgsResolved = buffers;
        }

        // -v threadtime: timestamps + tid, the only format that is actually debuggable
        // -f/-r/-n     : logcat rotates the files for us ("black box": no polling, no heap growth)
        java.util.List<String> argv = new ArrayList<String>();
        argv.add("logcat");
        for (String a : buffers) argv.add(a);
        argv.add("-v"); argv.add("threadtime");
        argv.add("-f"); argv.add(out.getAbsolutePath());
        argv.add("-r"); argv.add(String.valueOf(ROTATE_KB));
        argv.add("-n"); argv.add(String.valueOf(count));

        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            sProcess = pb.start();
            sRunning = true;
            sStartedAt = System.currentTimeMillis();
            sLastError = "";

            final Process proc = sProcess;
            sWaiter = new Thread(new Runnable() {
                public void run() {
                    try {
                        // Drain stdout so the child never blocks on a full pipe.
                        java.io.InputStream in = proc.getInputStream();
                        byte[] buf = new byte[8192];
                        while (in.read(buf) > 0) { /* discard */ }
                    } catch (Throwable ignored) { }
                    try { proc.waitFor(); } catch (Throwable ignored) { }
                    if (sProcess == proc) {
                        sRunning = false;
                        sProcess = null;
                    }
                }
            }, "blackbox-wait");
            sWaiter.setDaemon(true);
            sWaiter.start();

            return "started (" + count + " x " + ROTATE_KB + " KB = "
                    + Util.bytes((long) count * ROTATE_KB * 1024) + ", target window "
                    + minutes + " min)";
        } catch (Throwable t) {
            sRunning = false;
            sProcess = null;
            sLastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return "failed: " + sLastError;
        }
    }

    public static synchronized void stop() {
        sRunning = false;
        if (sProcess != null) {
            try { sProcess.destroy(); } catch (Throwable ignored) { }
            sProcess = null;
        }
        if (sWaiter != null) {
            try { sWaiter.join(2000); } catch (Throwable ignored) { }
            sWaiter = null;
        }
    }

    /** Applies new settings by restarting the recorder. */
    public static synchronized String restart(Context ctx, int totalBufferKb, int minutes) {
        stop();
        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        return start(ctx, totalBufferKb, minutes);
    }

    // -------------------------------------------------------------- rotation

    /** Rotated files, newest first. */
    private static List<File> filesNewestFirst(Context ctx) {
        File d = dir(ctx);
        File[] kids = d.listFiles();
        List<File> out = new ArrayList<File>();
        if (kids != null) {
            for (File k : kids) if (k.isFile() && k.length() > 0) out.add(k);
        }
        java.util.Collections.sort(out, new Comparator<File>() {
            public int compare(File a, File b) {
                long x = a.lastModified(), y = b.lastModified();
                return x < y ? 1 : (x > y ? -1 : 0);
            }
        });
        return out;
    }

    public static long sizeOnDisk(Context ctx) {
        long n = 0;
        for (File f : filesNewestFirst(ctx)) n += f.length();
        return n;
    }

    // --------------------------------------------------------------- extract

    public static final class Extract {
        public String text = "";
        public int    lines;
        public long   bytes;
        public int    filesUsed;
        public long   oldestMs;
        public long   newestMs;
        public String detail = "";
    }

    /**
     * Reads the recorded window back.
     *
     * @param minutes how far back to keep; 0 means "everything still on disk"
     */
    public static Extract extract(Context ctx, int minutes) {
        Extract ex = new Extract();
        List<File> files = filesNewestFirst(ctx);
        if (files.isEmpty()) {
            ex.detail = "no black box data on disk";
            return ex;
        }

        long cutoff = minutes > 0 ? System.currentTimeMillis() - (minutes * 60_000L) : 0;

        // Read newest-first but accumulate in reverse so the final text is chronological.
        List<String> collected = new ArrayList<String>();
        int used = 0;
        for (File f : files) {
            used++;
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 1 << 16);
                String line;
                // Buffered per file, then prepended, to keep memory bounded on big windows.
                List<String> fileLines = new ArrayList<String>();
                while ((line = br.readLine()) != null) {
                    long ts = parseThreadtime(line);
                    if (ts > 0) {
                        if (ex.oldestMs == 0 || ts < ex.oldestMs) ex.oldestMs = ts;
                        if (ts > ex.newestMs) ex.newestMs = ts;
                    }
                    if (cutoff > 0 && ts > 0 && ts < cutoff) continue;
                    fileLines.add(line);
                }
                collected.addAll(0, fileLines);
                if (ex.oldestMs > 0 && cutoff > 0 && ex.oldestMs <= cutoff) break;   // window covered
            } catch (Throwable t) {
                ex.detail += "error reading " + f.getName() + ": " + t + "; ";
            } finally {
                Util.close(br);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("black box log window\n");
        sb.append("====================\n");
        sb.append("extracted  : ").append(Util.human()).append("\n");
        sb.append("window     : last ").append(minutes > 0 ? (minutes + " minutes") : "everything on disk").append("\n");
        if (ex.oldestMs > 0 && ex.newestMs > 0) {
            sb.append("covers     : ").append(Util.human(ex.oldestMs)).append("  ..  ")
              .append(Util.human(ex.newestMs)).append("  (")
              .append((ex.newestMs - ex.oldestMs) / 1000).append(" s)\n");
        }
        sb.append("rotated files read: ").append(used).append("\n");
        sb.append("buffers    : ").append(Logcat.detail()).append("\n");
        sb.append("format     : threadtime\n");
        sb.append("\n");

        for (String l : collected) sb.append(l).append("\n");

        ex.text = sb.toString();
        ex.lines = collected.size();
        ex.bytes = ex.text.length();
        ex.filesUsed = used;
        return ex;
    }

    // ------------------------------------------------------------ timestamps

    private static final SimpleDateFormat THREADTIME =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    /**
     * Parses the leading {@code MM-dd HH:mm:ss.SSS} of a threadtime line into wall-clock millis.
     * Returns 0 when the line has no timestamp (continuation lines, headers).
     */
    static long parseThreadtime(String line) {
        if (line == null || line.length() < 18) return 0;
        // Quick shape check before paying for the parse.
        if (!(Character.isDigit(line.charAt(0)) && Character.isDigit(line.charAt(1))
                && line.charAt(2) == '-')) {
            return 0;
        }
        String head = line.substring(0, 18);
        try {
            java.util.Date d = THREADTIME.parse(head);
            if (d == null) return 0;
            // threadtime has no year: graft the current one on, tolerating a year boundary.
            Calendar now = Calendar.getInstance();
            Calendar c = Calendar.getInstance();
            c.setTime(d);
            c.set(Calendar.YEAR, now.get(Calendar.YEAR));
            // If that lands in the future by more than a day, the line is from last December.
            if (c.getTimeInMillis() - now.getTimeInMillis() > 24L * 3600 * 1000) {
                c.add(Calendar.YEAR, -1);
            }
            return c.getTimeInMillis();
        } catch (Throwable t) {
            return 0;
        }
    }

    // -------------------------------------------------------------- metadata

    /** Short status line for the UI. */
    public static String status(Context ctx) {
        if (!sRunning) {
            String e = sLastError.length() > 0 ? (" (last error: " + sLastError + ")") : "";
            return "未运行" + e;
        }
        long secs = (System.currentTimeMillis() - sStartedAt) / 1000;
        return "运行中 · 已录 " + fmtDuration(secs) + " · 占用 " + Util.bytes(sizeOnDisk(ctx));
    }

    static String fmtDuration(long secs) {
        if (secs < 60) return secs + " 秒";
        if (secs < 3600) return (secs / 60) + " 分 " + (secs % 60) + " 秒";
        return (secs / 3600) + " 时 " + ((secs % 3600) / 60) + " 分";
    }

    /** Sorted names of the files currently held, for diagnostics. */
    public static String describeFiles(Context ctx) {
        List<File> files = filesNewestFirst(ctx);
        if (files.isEmpty()) return "(empty)";
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            sb.append(String.format(Locale.US, "%-24s %10s  modified %s%n",
                    f.getName(), Util.bytes(f.length()), Util.human(f.lastModified())));
        }
        return sb.toString();
    }

    /** Used by tests/diagnostics: the raw file list newest-first. */
    public static List<File> rawFiles(Context ctx) {
        return Arrays.asList(filesNewestFirst(ctx).toArray(new File[0]));
    }
}
