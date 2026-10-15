package com.dsh.logcollector;

/**
 * Thin wrapper over {@link android.util.Log} so the app's own trace is greppable under one tag.
 *
 * <pre>adb logcat -s DSH-LC:V</pre>
 */
public final class Log {

    public static final String TAG = "DSH-LC";

    private Log() { }

    public static void line(String msg) {
        try { android.util.Log.i(TAG, msg); } catch (Throwable ignored) { }
    }

    public static void warn(String msg) {
        try { android.util.Log.w(TAG, msg); } catch (Throwable ignored) { }
    }

    public static void err(String msg, Throwable t) {
        try { android.util.Log.e(TAG, msg, t); } catch (Throwable ignored) { }
    }
}
