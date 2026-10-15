package com.dsh.logcollector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Works out which logcat buffer arguments this device actually understands.
 *
 * <p>{@code -b all} is the nicest way to say "every buffer", but it is not accepted by every
 * Android release, and the set of existing buffers differs between builds (some devices have no
 * {@code security} or {@code stats} buffer at all). So the device is probed once per process and
 * the answer is cached.
 *
 * <p><b>Threading contract:</b> {@link #bufferArgs()} never blocks. It returns the probed answer
 * when one is ready, and otherwise returns {@link #DEFAULT_BUFFERS} while kicking off a probe on a
 * background thread. Callers that are already on a background thread and want the accurate answer
 * should call {@link #probeBlocking()} first. This matters because
 * {@code CollectorService.onStartCommand} and {@code LogcatViewerActivity.onCreate} both run on the
 * main thread and must never spawn a dozen shell commands there.
 */
final class Logcat {

    private Logcat() { }

    /** Buffers worth trying, in the order they are usually available. */
    private static final String[] CANDIDATES = {
            "main", "system", "crash", "events", "radio", "security", "stats", "kernel"
    };

    /**
     * Used before the probe finishes. Repeated {@code -b} flags are accepted on every Android
     * release relevant here, so this is a safe starting point even if it is not the full set.
     */
    static final String[] DEFAULT_BUFFERS = { "-b", "main", "-b", "system", "-b", "crash" };

    private static volatile String[] sBufferArgs;
    private static volatile List<String> sAvailable;
    private static volatile String sDetail = "not probed yet (using main/system/crash)";
    private static volatile boolean sProbing;
    /** True when the probe found no buffers at all - a degraded, not a normal, outcome. */
    private static volatile boolean sProbeFailed;

    static boolean probeFailed() { return sProbeFailed; }

    /** Non-blocking. Safe to call from the main thread. */
    static String[] bufferArgs() {
        String[] cached = sBufferArgs;
        if (cached != null) return cached;
        ensureProbeStarted();
        return DEFAULT_BUFFERS;
    }

    /** Kicks off the probe at most once, on a daemon thread. */
    private static void ensureProbeStarted() {
        synchronized (Logcat.class) {
            if (sBufferArgs != null || sProbing) return;
            sProbing = true;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { probe(); } catch (Throwable t2) { Log.warn("logcat probe failed: " + t2); }
            }
        }, "logcat-probe");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Runs the probe now, on the calling thread. Intended for background workers that want the
     * accurate buffer set before doing real work. Idempotent and cheap once a result is cached.
     *
     * @return the argument array to use
     */
    static String[] probeBlocking() {
        String[] cached = sBufferArgs;
        if (cached != null) return cached;
        synchronized (Logcat.class) {
            if (sBufferArgs != null) return sBufferArgs;
            sProbing = true;
        }
        return probe();
    }

    private static String[] probe() {
        // --- preferred: a single "all" ---
        // -t is NOT used as the gate any more: it is absent before Android 6, and gating on it
        // made EVERY candidate fail on such a build, collapsing the capture to the main buffer
        // permanently and silently. A capability probe must use the most portable form.
        Shell.Result all = Shell.app("logcat -b all -d >/dev/null 2>&1 && echo ALL_OK", 20000);
        if (all.out.contains("ALL_OK")) {
            sAvailable = new ArrayList<String>(Arrays.asList(CANDIDATES));
            sDetail = "-b all is supported (all buffers captured)";
            sBufferArgs = new String[] { "-b", "all" };
            Log.line("logcat buffers: " + sDetail);
            return sBufferArgs;
        }

        // --- otherwise: one -b per buffer that actually exists ---
        List<String> ok = new ArrayList<String>();
        for (String b : CANDIDATES) {
            Shell.Result r = Shell.app("logcat -b " + b + " -d >/dev/null 2>&1 && echo B_OK", 12000);
            if (r.out.contains("B_OK")) ok.add(b);
        }
        if (ok.isEmpty()) {
            // Nothing responded. Do not silently pretend only "main" exists: record that the
            // whole determination failed so callers and verify.txt can see it.
            sProbeFailed = true;
            ok.add("main");
            Log.warn("logcat buffer probe found no buffers at all; assuming main only");
        }

        List<String> args = new ArrayList<String>();
        for (String b : ok) {
            args.add("-b");
            args.add(b);
        }
        sAvailable = ok;
        sDetail = "-b all rejected; probed " + ok.size() + " buffer(s): " + ok;
        sBufferArgs = args.toArray(new String[0]);
        Log.line("logcat buffers: " + sDetail);
        return sBufferArgs;
    }

    static List<String> availableBuffers() {
        List<String> a = sAvailable;
        if (a != null) return a;
        // Not probed yet: report what DEFAULT_BUFFERS covers.
        List<String> fallback = new ArrayList<String>();
        fallback.add("main");
        fallback.add("system");
        fallback.add("crash");
        return fallback;
    }

    static String detail() { return sDetail; }

    /**
     * The subset of {@link #availableBuffers()} worth writing as its own file.
     *
     * <p><b>The {@code kernel} buffer is deliberately KEPT.</b> An earlier version dropped it as
     * "duplicate of dmesg.txt", which was wrong: on any Android 11+ device the app cannot read
     * {@code /dev/kmsg} or {@code dmesg} at all (seccomp denies the syslog syscall), so
     * {@code logcat -b kernel} is the <i>only</i> kernel-log channel that works without root.
     * Dropping it is why kernel messages appeared to be missing entirely.
     *
     * <p>Only {@code stats} is still skipped - it carries periodic framework counters rather than
     * anything diagnostic.
     */
    static List<String> reportableBuffers() {
        List<String> out = new ArrayList<String>();
        for (String b : availableBuffers()) {
            if ("stats".equals(b)) continue;
            out.add(b);
        }
        if (out.isEmpty()) out.add("main");
        return out;
    }
}
