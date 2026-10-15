package com.dsh.logcollector;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Watches the {@code events} and {@code crash} log buffers for crashes and ANRs and triggers an
 * automatic capture when one happens.
 *
 * <p>The {@code events} buffer is the reliable signal: the activity manager writes structured
 * {@code am_crash}, {@code am_anr}, {@code am_native_crash} and {@code am_kill} records there even
 * when the app's own log lines have already rotated away. The {@code crash} buffer is used as a
 * second, human-readable source.
 *
 * <p>Capture storms are prevented by a debounce window and an hourly ceiling.
 */
public final class CrashWatcher {

    private CrashWatcher() { }

    public interface Listener {
        /**
         * @param kind   "crash", "anr" or "kill"
         * @param detail the raw log line that triggered it
         */
        void onEvent(String kind, String detail);
    }

    private static volatile boolean sRunning;
    private static Thread sEventsThread;
    private static Thread sCrashThread;
    private static Process sEventsProc;
    private static Process sCrashProc;
    private static Listener sListener;
    private static Handler sMain;

    private static volatile long sLastFireMs;
    private static volatile long sStartedAtMs;
    private static volatile long sWarmupUntilMs;
    private static final long DEBOUNCE_MS = 20_000L;
    /**
     * Grace period after start during which the pre-existing contents of the events buffer are
     * ignored. Long enough to cover the initial buffer dump, short enough not to miss a real
     * crash shortly after launch.
     */
    private static final long WARMUP_MS = 8_000L;
    private static final long WINDOW_MS = 60 * 60 * 1000L;
    private static final java.util.concurrent.atomic.AtomicInteger sIgnoredBacklog =
            new java.util.concurrent.atomic.AtomicInteger();
    private static volatile boolean sIgnoreSummaryScheduled;
    private static final int  MAX_PER_HOUR = 10;
    private static final List<Long> sFires = new ArrayList<Long>();

    public static boolean isRunning() { return sRunning; }

    public static synchronized void start(Context ctx, Listener listener) {
        if (sRunning) {
            sListener = listener;
            return;
        }
        sListener = listener;
        if (sMain == null) sMain = new Handler(Looper.getMainLooper());
        sRunning = true;
        // Only react to events that arrive AFTER we started. `logcat -b events` opens with the
        // whole existing buffer, and without this the watcher immediately "detects" an old crash
        // from hours or days ago and fires a pointless automatic capture on every launch.
        // Observed on a moto g(100): a 10-13 event triggered a run on 10-14 at startup.
        sStartedAtMs = System.currentTimeMillis();
        sWarmupUntilMs = sStartedAtMs + WARMUP_MS;

        sEventsThread = spawn("logcat-events", new String[] {
                "logcat", "-b", "events", "-v", "threadtime"
        }, true);
        sCrashThread = spawn("logcat-crash", new String[] {
                "logcat", "-b", "crash", "-v", "threadtime"
        }, false);
    }

    public static synchronized void stop() {
        sRunning = false;
        for (Process p : new Process[] { sEventsProc, sCrashProc }) {
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) { } }
        }
        sEventsProc = null;
        sCrashProc = null;
        if (sEventsThread != null) { try { sEventsThread.join(1500); } catch (Throwable ignored) { } sEventsThread = null; }
        if (sCrashThread != null) { try { sCrashThread.join(1500); } catch (Throwable ignored) { } sCrashThread = null; }
    }

    private static Thread spawn(final String name, final String[] argv, final boolean eventsBuffer) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                Process p = null;
                try {
                    ProcessBuilder pb = new ProcessBuilder(argv);
                    pb.redirectErrorStream(true);
                    p = pb.start();
                    if (eventsBuffer) sEventsProc = p; else sCrashProc = p;

                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream(), "UTF-8"), 1 << 15);
                    String line;
                    int crashContext = 0;
                    StringBuilder crashBuf = new StringBuilder();
                    while (sRunning && (line = br.readLine()) != null) {
                        if (eventsBuffer) {
                            handleEventLine(line);
                        } else {
                            // Multi-line FATAL EXCEPTION blocks: grab the header plus a few lines.
                            if (line.contains("FATAL EXCEPTION") || line.contains("beginning of crash")
                                    || line.contains("Fatal signal")) {
                                crashContext = 12;
                                crashBuf.setLength(0);
                                crashBuf.append(line).append("\n");
                                fire("crash", line, null);
                            } else if (crashContext > 0) {
                                crashContext--;
                                crashBuf.append(line).append("\n");
                            }
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (p != null) { try { p.destroy(); } catch (Throwable ignored) { } }
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void handleEventLine(String line) {
        if (line == null || line.length() == 0) return;

        // am_crash: [user, pid, name, flags, exception, message, file, line]
        if (line.contains("am_crash")) {
            fire("crash", line, describeAmCrash(line));
            return;
        }
        if (line.contains("am_anr")) {
            fire("anr", line, describeAmAnr(line));
            return;
        }
        if (line.contains("am_native_crash")) {
            fire("crash", line, "native crash: " + tail(line, 200));
            return;
        }
        // NOTE: am_proc_died is deliberately NOT a trigger.
        //
        // It fires for *every* ordinary process exit - background app reaping, task cleanup,
        // the low-memory killer, a user swiping an app away. On the test device it fired
        // hundreds of times per hour, so treating it as a crash signal produced spurious
        // "auto-crash" collections during completely normal operation (observed twice).
        // Genuine crashes are already covered by am_crash / am_anr / am_native_crash, which are
        // specific and reliable. am_kill is kept only as a low-priority hint, and is filtered to
        // the explicit low-memory reason rather than any kill.
        if (line.contains("am_kill") && line.contains("low memory")) {
            fire("kill", line, "low-memory kill: " + tail(line, 200));
            return;
        }
        if (line.contains("watchdog") || line.contains("Watchdog")) {
            fire("crash", line, "watchdog: " + tail(line, 200));
        }
    }

    /** Turns an {@code am_crash} record into something readable. */
    private static String describeAmCrash(String line) {
        try {
            int i = line.indexOf("am_crash");
            String rest = line.substring(i + "am_crash".length()).trim();
            // rest looks like ": [0,1234,com.foo,...]"
            int lb = rest.indexOf('[');
            int rb = rest.lastIndexOf(']');
            if (lb >= 0 && rb > lb) {
                String[] parts = rest.substring(lb + 1, rb).split(",");
                if (parts.length >= 6) {
                    String pid = parts[1].trim();
                    String pkg = parts[2].trim();
                    String exc = parts[4].trim();
                    String msg = parts[5].trim();
                    String file = parts.length > 6 ? parts[6].trim() : "";
                    String ln = parts.length > 7 ? parts[7].trim() : "";
                    return "崩溃: " + pkg + " (pid " + pid + ")\n  " + exc + ": " + msg
                         + (file.length() > 0 ? "\n  at " + file + ":" + ln : "");
                }
            }
        } catch (Throwable ignored) { }
        return "崩溃: " + tail(line, 300);
    }

    private static String describeAmAnr(String line) {
        try {
            int i = line.indexOf("am_anr");
            String rest = line.substring(i + "am_anr".length()).trim();
            int lb = rest.indexOf('[');
            int rb = rest.lastIndexOf(']');
            if (lb >= 0 && rb > lb) {
                String[] parts = rest.substring(lb + 1, rb).split(",");
                if (parts.length >= 5) {
                    return "ANR: " + parts[2].trim() + " (pid " + parts[1].trim() + ")\n  "
                         + parts[4].trim();
                }
            }
        } catch (Throwable ignored) { }
        return "ANR: " + tail(line, 300);
    }

    // ------------------------------------------------------------------ fire

    private static void fire(String kind, String rawLine, String pretty) {
        long now = System.currentTimeMillis();

        // Ignore the buffer backlog that logcat replays on startup.
        if (now < sWarmupUntilMs) {
            countIgnored();
            return;
        }

        // Belt and braces: if the line carries its own timestamp and that timestamp is older than
        // the warmup instant, it is a replayed event, not something happening now.
        long lineMs = Blackbox.parseThreadtime(rawLine);
        if (lineMs > 0 && lineMs < sStartedAtMs) {
            countIgnored();
            return;
        }

        synchronized (CrashWatcher.class) {
            if (now - sLastFireMs < DEBOUNCE_MS) return;
            // prune the rolling window
            for (int i = sFires.size() - 1; i >= 0; i--) {
                if (now - sFires.get(i) > WINDOW_MS) sFires.remove(i);
            }
            if (sFires.size() >= MAX_PER_HOUR) return;
            sFires.add(now);
            sLastFireMs = now;
        }

        final String detail = (pretty != null ? pretty : tail(rawLine, 300));
        final Listener l = sListener;
        if (l != null) {
            if (sMain != null) {
                sMain.post(new Runnable() {
                    public void run() {
                        try { l.onEvent(kind, detail); } catch (Throwable ignored) { }
                    }
                });
            } else {
                try { l.onEvent(kind, detail); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * Counts suppressed backlog events instead of logging each one.
     *
     * <p>The {@code events} buffer on a busy phone replays hundreds of {@code am_proc_died}
     * records at startup. Emitting a log line for every one of them floods logcat with the
     * collector's own chatter - which is exactly the noise this app exists to avoid. One summary
     * line is emitted a few seconds later instead.
     */
    private static void countIgnored() {
        sIgnoredBacklog.incrementAndGet();
        if (!sIgnoreSummaryScheduled) {
            sIgnoreSummaryScheduled = true;
            final Handler h = sMain;
            if (h != null) {
                h.postDelayed(new Runnable() {
                    public void run() {
                        int n = sIgnoredBacklog.getAndSet(0);
                        sIgnoreSummaryScheduled = false;
                        Log.line("ignored " + n + " pre-existing event(s) from the logcat backlog");
                    }
                }, WARMUP_MS + 2_000L);
            }
        }
    }

    /** Number of automatic captures still allowed inside the current window. */
    public static int budgetRemaining() {
        long now = System.currentTimeMillis();
        synchronized (CrashWatcher.class) {
            int n = 0;
            for (int i = sFires.size() - 1; i >= 0; i--) {
                if (now - sFires.get(i) > WINDOW_MS) sFires.remove(i); else n++;
            }
            return MAX_PER_HOUR - n;
        }
    }

    public static String statusLine() {
        if (!sRunning) return "未运行";
        return "监听中 · 本小时剩余自动采集 " + budgetRemaining() + " 次";
    }

    private static String tail(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : "..." + s.substring(s.length() - n);
    }
}
