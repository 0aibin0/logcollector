package com.dsh.logcollector;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Layered command execution.
 *
 * <p>Three ways to run a diagnostic command, tried in this order by
 * {@link #run(String, long)}:
 * <ol>
 *   <li><b>root</b> - {@code su -c "..."} when a working {@code su} is present. This is the only
 *       mode that can read {@code dmesg}, run a full {@code dumpsys}, and touch {@code debugfs}.</li>
 *   <li><b>app</b> - the command runs as this app's own uid. Works for whatever the platform
 *       allows a normal (or {@code pm grant}ed) app to see.</li>
 * </ol>
 *
 * <p>Output is always captured in full, and a hard timeout guarantees a wedged command can never
 * hang a collection run: on timeout the process is destroyed and whatever was captured so far is
 * returned with {@link Result#timedOut} set.
 */
public final class Shell {

    public static final int MODE_ROOT = 0;
    public static final int MODE_APP  = 1;

    private Shell() { }

    // ------------------------------------------------------------- threading

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ExecutorService POOL = Executors.newCachedThreadPool(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "shell-" + SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    // ---------------------------------------------------------------- result

    public static final class Result {
        public final String cmd;
        public final int mode;
        public final int code;
        public final String out;
        public final String err;
        public final long ms;
        public final boolean timedOut;

        Result(String cmd, int mode, int code, String out, String err, long ms, boolean timedOut) {
            this.cmd = cmd;
            this.mode = mode;
            this.code = code;
            this.out = out == null ? "" : out;
            this.err = err == null ? "" : err;
            this.ms = ms;
            this.timedOut = timedOut;
        }

        public boolean ok() { return code == 0 && !timedOut; }

        /** stdout when the command worked, otherwise the most useful error text available. */
        public String text() {
            if (out.length() > 0) return out;
            if (err.length() > 0) return err;
            return "";
        }

        public String modeName() { return mode == MODE_ROOT ? "root" : "app"; }

        /** A short one-line description used in the run index. */
        public String summary() {
            if (timedOut) return "TIMEOUT after " + ms + "ms (" + modeName() + ")";
            if (code != 0) return "exit=" + code + " (" + modeName() + ")"
                    + (err.length() > 0 ? " :: " + firstLine(err) : "");
            return "ok (" + modeName() + ", " + Util.bytes(out.length()) + ")";
        }

        @Override public String toString() {
            return "[" + modeName() + " exit=" + code + (timedOut ? " TIMEOUT" : "") + "] " + cmd;
        }
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        String l = i < 0 ? s : s.substring(0, i);
        return l.length() > 160 ? l.substring(0, 160) + "..." : l.trim();
    }

    // ------------------------------------------------------------- detection

    private static volatile Boolean sHasRoot;
    private static volatile String sRootDetail = "not probed";
    /** Which su invocation actually worked, or null when none did. */
    private static volatile String[] sSuPrefix;

    /**
     * The {@code su} command forms found in the wild, tried in order.
     *
     * <p>There is no single {@code su} syntax on Android:
     * <ul>
     *   <li><b>AOSP</b> ({@code /system/xbin/su} on userdebug/eng builds) takes
     *       {@code su [WHO [COMMAND...]]} and <b>rejects {@code -c} outright</b> with
     *       {@code invalid uid/gid '-c'}. Verified on the moto g(100) test device. It then
     *       does a plain {@code execvp(argv)}, so a compound command must be handed to an
     *       explicit {@code sh -c}.</li>
     *   <li><b>Magisk / SuperSU / phh</b> take the classic {@code su -c 'command'}.</li>
     * </ul>
     *
     * <p>Every form therefore ends in {@code -c}, and the command is always appended as one
     * argument. That keeps the probe and the real invocation on exactly the same contract, so a
     * dialect that passes the probe cannot fail later on a pipeline or {@code &&}.
     */
    private static final String[][] SU_FORMS = {
            { "su", "-c" },                // Magisk, SuperSU, most third-party su
            { "su", "0", "sh", "-c" },     // AOSP su: explicit uid, then a real shell
            { "su", "root", "sh", "-c" },  // su that wants the user name spelled out
    };

    /** Cached {@code su} probe. Runs the real thing once per process. */
    public static boolean hasRoot() {
        Boolean cached = sHasRoot;
        if (cached != null) return cached;
        synchronized (Shell.class) {
            if (sHasRoot != null) return sHasRoot;

            String lastErr = "";
            for (String[] form : SU_FORMS) {
                String[] argv = concat(form, new String[] { "id" });
                Result r = raw(argv, 6000);
                if (r.code == 0 && r.out.contains("uid=0")) {
                    sSuPrefix = form;
                    sRootDetail = "su works via `" + Util.join(" ", java.util.Arrays.asList(form))
                                + "` -> " + firstLine(r.out);
                    sHasRoot = Boolean.TRUE;
                    Log.line("root: " + sRootDetail);
                    return true;
                }
                String e = (r.out + " " + r.err).trim();
                if (e.length() > 0 && lastErr.length() == 0) lastErr = firstLine(e);
            }

            sSuPrefix = null;
            sHasRoot = Boolean.FALSE;
            sRootDetail = "no working su"
                    + (lastErr.length() > 0 ? (" - last error: " + lastErr) : "");
            Log.line("root: " + sRootDetail);
            return false;
        }
    }

    /**
     * The full root diagnosis, including the {@code su} binary inspection.
     *
     * <p>Kept separate from {@link #hasRoot()} because the inspection spawns a command. Calling it
     * during the probe meant the extra {@code ls} could run before the app had finished starting
     * up (and, on a denied path, before we even knew it was denied), so the explanation silently
     * never made it into the report. Reports call this lazily instead.
     */
    public static String rootDiagnosis() {
        if (sHasRoot == null) hasRoot();
        String base = sRootDetail;
        if (Boolean.TRUE.equals(sHasRoot)) return base;
        if (base.contains("su binary:")) return base;   // already enriched
        return base + describeSuDenial();
    }

    private static volatile String sSuDenialCache;

    /**
     * Explains a {@code su} that exists but cannot be executed, which is the situation on a
     * stock userdebug build.
     *
     * <p>Two DAC layers deny a normal app here, both visible on the moto g(100):
     * <pre>
     *   /system/xbin   drwxr-x---  root shell    &lt;- the app cannot even enter the directory
     *     └─ su        -rwsr-x---  root shell    &lt;- nor read the file if it could
     * </pre>
     * A normal app uid is in neither {@code root} nor {@code shell}, so {@code execve("su")} fails
     * with EACCES before {@code su} ever runs.
     *
     * <p>The probe therefore inspects the <b>directories</b>, not the {@code su} file: stat()ing
     * {@code /system/xbin} only needs {@code +x} on its parent {@code /system}, which is
     * world-executable - whereas listing the file itself returns nothing under an app uid and
     * silently produced an empty explanation.
     */
    private static String describeSuDenial() {
        String cached = sSuDenialCache;
        if (cached != null) return cached;
        String result = "";
        try {
            Result r = app("ls -ld /system/xbin /system/bin /sbin /su 2>/dev/null", 8000);
            String listing = r.out.trim();
            boolean xbinLocked = listing.contains("/system/xbin")
                    && (listing.contains("drwxr-x---") || listing.contains("root shell"));

            StringBuilder sb = new StringBuilder();
            if (listing.length() > 0) {
                sb.append("\n  su location modes:");
                for (String line : listing.split("\n")) {
                    if (line.trim().length() > 0) sb.append("\n    ").append(line.trim());
                }
            }
            if (xbinLocked || listing.length() > 0) {
                sb.append("\n  Where su lives is mode drwxr-x--- root:shell, so the binary is\n")
                  .append("  executable and even readable only by root and by members of the\n")
                  .append("  `shell` group. A normal app uid belongs to neither: execve() fails\n")
                  .append("  with EACCES before su ever runs.\n")
                  .append("\n  `adb root` does NOT change this. It only restarts adbd so the *adb\n")
                  .append("  shell* runs as uid=0 in the su SELinux domain. It grants an\n")
                  .append("  application no additional rights whatsoever.\n")
                  .append("\n  For this app to run su, the device needs a root manager\n")
                  .append("  (Magisk / SuperSU / KernelSU), which installs a world-executable su\n")
                  .append("  and prompts the user to authorise each app.\n")
                  .append("\n  Without that, the parts of a collection that need root stay\n")
                  .append("  unavailable in-app - use tools/augment_from_pc.ps1 instead, which\n")
                  .append("  runs the same commands through adb where none of these limits apply.");
            }
            result = sb.toString();
        } catch (Throwable ignored) { }
        sSuDenialCache = result;
        return result;
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public static String rootDetail() { return sRootDetail; }

    public static void resetRootCache() {
        synchronized (Shell.class) {
            sHasRoot = null;
            sSuPrefix = null;
            sRootDetail = "not probed";
        }
    }

    // ---------------------------------------------------------------- invoke

    /** Runs through {@code su} if possible, otherwise as the app. */
    public static Result run(String cmd, long timeoutMs) {
        Result guarded = dmesgGuard(cmd);
        if (guarded != null) return guarded;
        if (hasRoot()) return su(cmd, timeoutMs);
        return app(cmd, timeoutMs);
    }

    /** Tries root, falls back to the app, and reports which one actually produced the output. */
    public static Result runReported(String cmd, long timeoutMs) {
        Result guarded = dmesgGuard(cmd);
        if (guarded != null) return guarded;
        if (hasRoot()) {
            Result r = su(cmd, timeoutMs);
            if (r.code == 0 || r.out.length() > 0) return r;
            Result a = app(cmd, timeoutMs);
            if (a.out.length() > 0) return a;
            return r;
        }
        return app(cmd, timeoutMs);
    }

    /**
     * Central safety net: refuses to spawn {@code dmesg} as this app once the device has told us
     * it will be killed by seccomp.
     *
     * <p>Placed here rather than at each call site so that no future code path can accidentally
     * reintroduce the tombstone-overwriting behaviour. See {@link DmesgGate} for why one doomed
     * {@code dmesg} call is genuinely harmful rather than merely useless.
     *
     * <p>Root is exempt on purpose: {@code su} is forked by the root daemon into its own domain,
     * which does not inherit this app's seccomp filter, so {@code su -c dmesg} genuinely works
     * where the app-uid path does not.
     *
     * @return a synthetic failure Result when the command must not run, or null to proceed
     */
    private static Result dmesgGuard(String cmd) {
        if (!DmesgGate.isBlocked()) return null;
        if (!DmesgGate.commandUsesDmesg(cmd)) return null;
        if (hasRoot()) return null;
        return new Result(cmd, MODE_APP, 159, DmesgGate.skipNotice(), "", 0, false);
    }

    /** Runs as this app's uid via {@code sh -c}. */
    public static Result app(String cmd, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        Result r = raw(new String[] { "sh", "-c", cmd }, timeoutMs);
        return new Result(cmd, MODE_APP, r.code, r.out, r.err, r.ms, r.timedOut);
    }

    /**
     * Runs as root, using whichever {@code su} dialect this device accepted during the probe.
     *
     * <p>The command is always executed through {@code sh -c} so that shell syntax (pipes,
     * redirects, {@code &&}) works on every {@code su} variant. Passing the whole string as a
     * single argv element is what the old AOSP {@code su} expects, while Magisk-style
     * {@code su -c} takes it as one argument too - so {@code sh -c <cmd>} is the common ground.
     */
    public static Result su(String cmd, long timeoutMs) {
        String[] prefix = sSuPrefix;
        if (prefix == null) {
            // Not probed yet, or probing failed. Probe once so the correct dialect is used.
            if (!hasRoot()) {
                return new Result(cmd, MODE_ROOT, -1, "",
                        "no working su: " + sRootDetail, 0, false);
            }
            prefix = sSuPrefix;
            if (prefix == null) {
                return new Result(cmd, MODE_ROOT, -1, "", "su dialect unknown", 0, false);
            }
        }
        long t0 = System.currentTimeMillis();
        Result r = raw(concat(prefix, new String[] { cmd }), timeoutMs);
        return new Result(cmd, MODE_ROOT, r.code, r.out, r.err, r.timedOut ? r.ms
                : (System.currentTimeMillis() - t0), r.timedOut);
    }

    public static Result sh(String cmd) { return app(cmd, 0); }
    public static Result sh(String cmd, long timeoutMs) { return app(cmd, timeoutMs); }

    // ------------------------------------------------------------------- core

    /**
     * Spawns {@code argv} and captures everything. A timeout of 0 means "use the default";
     * a negative timeout means "wait forever" (used only for the black-box recorder's child).
     */
    static Result raw(final String[] argv, long timeoutMs) {
        final long t0 = System.currentTimeMillis();
        final StringBuilder cmdText = new StringBuilder();
        for (int i = 0; i < argv.length; i++) {
            if (i > 0) cmdText.append(' ');
            cmdText.append(argv[i]);
        }
        final String cmd = cmdText.toString();

        final long timeout = timeoutMs == 0 ? 60000L : timeoutMs;

        Callable<Result> job = new Callable<Result>() {
            public Result call() {
                Process p = null;
                StreamGobbler errGobbler = null;
                try {
                    ProcessBuilder pb = new ProcessBuilder(argv);
                    pb.redirectErrorStream(false);
                    p = pb.start();
                    final Process proc = p;

                    errGobbler = new StreamGobbler(proc.getErrorStream());
                    errGobbler.start();

                    String out = readAll(proc.getInputStream());

                    int code;
                    try {
                        code = proc.waitFor();
                    } catch (InterruptedException ie) {
                        proc.destroy();
                        code = -1;
                    }
                    errGobbler.join(2000);
                    return new Result(cmd, MODE_APP, code, out, errGobbler.text(),
                            System.currentTimeMillis() - t0, false);
                } catch (Throwable t) {
                    String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
                    if (p != null) {
                        try { p.destroy(); } catch (Throwable ignored) { }
                    }
                    return new Result(cmd, MODE_APP, -1, "", msg,
                            System.currentTimeMillis() - t0, false);
                }
            }
        };

        Future<Result> f = POOL.submit(job);
        try {
            if (timeout < 0) return f.get();
            return f.get(timeout, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            f.cancel(true);
            killStray(argv);
            return new Result(cmd, MODE_APP, -1, "", "timed out after " + timeout + "ms",
                    System.currentTimeMillis() - t0, true);
        } catch (Throwable t) {
            f.cancel(true);
            String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
            return new Result(cmd, MODE_APP, -1, "", msg, System.currentTimeMillis() - t0, false);
        }
    }

    /**
     * Best-effort cleanup of an orphaned child when a timeout fires.
     *
     * <p>Deliberately narrow: only {@code dumpsys} and {@code bugreport} are reaped. A blanket
     * {@code pkill logcat} would kill the user's own logcat sessions and the app's black-box
     * recorder along with the stray child, which is far worse than leaving one process behind.
     */
    private static void killStray(String[] argv) {
        try {
            String bin = argv[0];
            if ("dumpsys".equals(bin) || "bugreport".equals(bin)) {
                app("pkill -f '^" + bin + "' 2>/dev/null", 5000);
            }
        } catch (Throwable ignored) { }
    }

    private static String readAll(InputStream in) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(65536);
        byte[] buf = new byte[32768];
        try {
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } catch (Throwable ignored) { }
        try {
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return new String(bos.toByteArray());
        }
    }

    /**
     * Pipes an arbitrary string into a command's stdin. Used to write files that the app itself
     * is not allowed to create (for example {@code /sdcard/log} on a scoped-storage build).
     */
    public static Result pipe(String cmd, String stdin, long timeoutMs) {
        final String text = stdin == null ? "" : stdin;
        long t0 = System.currentTimeMillis();
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            p = pb.start();
            final Process proc = p;
            StreamGobbler err = new StreamGobbler(proc.getErrorStream());
            err.start();
            OutputStream os = proc.getOutputStream();
            os.write(text.getBytes("UTF-8"));
            os.flush();
            Util.close(os);
            String out = readAll(proc.getInputStream());
            int code = proc.waitFor();
            err.join(2000);
            if (timeoutMs > 0 && System.currentTimeMillis() - t0 > timeoutMs) {
                proc.destroy();
            }
            return new Result(cmd, MODE_APP, code, out, err.text(),
                    System.currentTimeMillis() - t0, false);
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) { } }
            return new Result(cmd, MODE_APP, -1, "", msg, System.currentTimeMillis() - t0, false);
        }
    }

    /** Reads a stream on its own thread so big stderr output can never deadlock the parent. */
    static final class StreamGobbler extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);

        StreamGobbler(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override public void run() {
            byte[] b = new byte[8192];
            try {
                int n;
                while ((n = in.read(b)) > 0) {
                    // Cap what we keep so a runaway error stream cannot eat the heap.
                    if (buf.size() < 256 * 1024) buf.write(b, 0, n);
                }
            } catch (Throwable ignored) { }
        }

        String text() {
            try { return new String(buf.toByteArray(), "UTF-8"); }
            catch (Throwable t) { return new String(buf.toByteArray()); }
        }
    }
}
