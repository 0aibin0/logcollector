package com.dsh.logcollector;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * All app configuration lives here, backed by one SharedPreferences file.
 * Every setter is mirrored into a plain field cache so the collectors can read
 * settings from background threads without touching SharedPreferences again.
 */
public final class Prefs {

    private static final String FILE = "logcollector";

    public static final String KEY_BASE_DIR        = "base_dir";
    public static final String KEY_LOG_BUFFERS     = "log_buffers";
    public static final String KEY_LOG_TAIL_KB     = "log_tail_kb";
    public static final String KEY_DMESG_TAIL_KB   = "dmesg_tail_kb";
    public static final String KEY_DUMPSYS_FULL    = "dumpsys_full";
    public static final String KEY_DUMPSYS_EXTRA   = "dumpsys_extra";
    public static final String KEY_DO_SCREENSHOT   = "do_screenshot";
    public static final String KEY_DO_RECORD       = "do_record";
    public static final String KEY_RECORD_SECONDS  = "record_seconds";
    public static final String KEY_DO_BUGREPORT    = "do_bugreport";
    public static final String KEY_DO_KERNEL       = "do_kernel";
    public static final String KEY_DO_DISPLAY      = "do_display";
    public static final String KEY_DO_PROP         = "do_prop";
    public static final String KEY_DO_CMDLINE      = "do_cmdline";
    public static final String KEY_SHELL_FALLBACK  = "shell_fallback";
    public static final String KEY_KEEP_RUNS       = "keep_runs";

    public static final String KEY_BLACKBOX_ON     = "blackbox_on";
    public static final String KEY_BLACKBOX_MIN    = "blackbox_minutes";
    public static final String KEY_BLACKBOX_BUF    = "blackbox_buffer_kb";

    public static final String KEY_AUTO_BOOT       = "auto_boot";
    public static final String KEY_AUTO_CRASH      = "auto_crash";
    public static final String KEY_AUTO_ANR        = "auto_anr";
    public static final String KEY_TIMER_ON        = "timer_on";
    public static final String KEY_TIMER_MIN       = "timer_minutes";
    public static final String KEY_TIMER_KEEP      = "timer_keep";

    public static final String KEY_UPLOAD_URL      = "upload_url";
    public static final String KEY_UPLOAD_TOKEN    = "upload_token";
    public static final String KEY_AUTO_UPLOAD     = "auto_upload";

    /**
     * Persisted verdict that {@code dmesg} is unusable on this device.
     *
     * <p>Kept across restarts on purpose: every doomed {@code dmesg} attempt makes the kernel
     * write a tombstone, and the tombstone ring only holds ~32 entries, so re-probing on each
     * launch would silently destroy the device's native crash history. See {@link DmesgGate}.
     */
    public static final String KEY_DMESG_BLOCKED   = "dmesg_blocked";
    public static final String KEY_DMESG_REASON    = "dmesg_blocked_reason";
    public static final String KEY_DMESG_AT        = "dmesg_blocked_at";

    // ------------------------------------------------------------- defaults

    public static final String DEF_BASE_DIR    = "/sdcard/log";
    public static final int    DEF_LOG_TAIL_KB = 4096;
    public static final int    DEF_DMESG_TAIL_KB = 2048;
    public static final int    DEF_RECORD_SEC  = 15;
    public static final int    DEF_KEEP_RUNS   = 20;

    public static final int    DEF_BLACKBOX_MIN = 10;
    public static final int    DEF_BLACKBOX_BUF = 8192;
    public static final int    DEF_TIMER_MIN    = 30;
    public static final int    DEF_TIMER_KEEP   = 10;

    /**
     * dumpsys services worth dumping on every run.
     *
     * <p>Deliberately NOT treated as a hard list:  collectors intersect it with the device's own
     * {@code dumpsys -l} output, because these names are far from universal. {@code cpuinfo} is
     * gone from AOSP 8+, {@code netstats} was renamed long ago, and {@code thermalservice} /
     * {@code color_display} / {@code displayfeature} are Qualcomm- or vendor-specific. Running a
     * name that does not exist just yields "Can't find service".
     */
    public static final String[] CORE_DUMPSYS = {
            "battery", "batterystats", "power", "meminfo", "cpuinfo", "procstats",
            "activity", "window", "display", "input", "connectivity", "wifi", "netstats",
            "package", "thermalservice", "thermal", "sensorservice", "alarm", "jobscheduler",
            "mount", "diskstats", "usagestats", "deviceidle", "notification", "media.audio_flinger"
    };

    /** Extra services that only make sense on a display / panel bring-up build. */
    public static final String[] DISPLAY_DUMPSYS = {
            "SurfaceFlinger", "display", "window", "input", "power", "battery",
            "color_display", "displayfeature", "overlay", "gpu", "media.camera"
    };

    // -------------------------------------------------------------- storage

    private final SharedPreferences sp;

    private Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static volatile Prefs INSTANCE;

    public static Prefs get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (Prefs.class) {
                if (INSTANCE == null) INSTANCE = new Prefs(ctx);
            }
        }
        return INSTANCE;
    }

    // ------------------------------------------------------------- accessors

    public String baseDir()      { return sp.getString(KEY_BASE_DIR, DEF_BASE_DIR); }
    public void baseDir(String v) { sp.edit().putString(KEY_BASE_DIR, v).apply(); }

    public boolean logBuffers()  { return sp.getBoolean(KEY_LOG_BUFFERS, true); }
    public void logBuffers(boolean v) { sp.edit().putBoolean(KEY_LOG_BUFFERS, v).apply(); }

    public int logTailKb()       { return sp.getInt(KEY_LOG_TAIL_KB, DEF_LOG_TAIL_KB); }
    public void logTailKb(int v) { sp.edit().putInt(KEY_LOG_TAIL_KB, v).apply(); }

    public int dmesgTailKb()     { return sp.getInt(KEY_DMESG_TAIL_KB, DEF_DMESG_TAIL_KB); }
    public void dmesgTailKb(int v) { sp.edit().putInt(KEY_DMESG_TAIL_KB, v).apply(); }

    public boolean dumpsysFull() { return sp.getBoolean(KEY_DUMPSYS_FULL, true); }
    public void dumpsysFull(boolean v) { sp.edit().putBoolean(KEY_DUMPSYS_FULL, v).apply(); }

    public boolean dumpsysExtra() { return sp.getBoolean(KEY_DUMPSYS_EXTRA, true); }
    public void dumpsysExtra(boolean v) { sp.edit().putBoolean(KEY_DUMPSYS_EXTRA, v).apply(); }

    public boolean doScreenshot() { return sp.getBoolean(KEY_DO_SCREENSHOT, true); }
    public void doScreenshot(boolean v) { sp.edit().putBoolean(KEY_DO_SCREENSHOT, v).apply(); }

    public boolean doRecord()    { return sp.getBoolean(KEY_DO_RECORD, false); }
    public void doRecord(boolean v) { sp.edit().putBoolean(KEY_DO_RECORD, v).apply(); }

    public int recordSeconds()   { return sp.getInt(KEY_RECORD_SECONDS, DEF_RECORD_SEC); }
    public void recordSeconds(int v) { sp.edit().putInt(KEY_RECORD_SECONDS, v).apply(); }

    public boolean doBugreport() { return sp.getBoolean(KEY_DO_BUGREPORT, false); }
    public void doBugreport(boolean v) { sp.edit().putBoolean(KEY_DO_BUGREPORT, v).apply(); }

    public boolean doKernel()    { return sp.getBoolean(KEY_DO_KERNEL, true); }
    public void doKernel(boolean v) { sp.edit().putBoolean(KEY_DO_KERNEL, v).apply(); }

    public boolean doDisplay()   { return sp.getBoolean(KEY_DO_DISPLAY, true); }
    public void doDisplay(boolean v) { sp.edit().putBoolean(KEY_DO_DISPLAY, v).apply(); }

    public boolean doProp()      { return sp.getBoolean(KEY_DO_PROP, true); }
    public void doProp(boolean v) { sp.edit().putBoolean(KEY_DO_PROP, v).apply(); }

    public boolean doCmdline()   { return sp.getBoolean(KEY_DO_CMDLINE, true); }
    public void doCmdline(boolean v) { sp.edit().putBoolean(KEY_DO_CMDLINE, v).apply(); }

    public boolean shellFallback() { return sp.getBoolean(KEY_SHELL_FALLBACK, true); }
    public void shellFallback(boolean v) { sp.edit().putBoolean(KEY_SHELL_FALLBACK, v).apply(); }

    public int keepRuns()        { return sp.getInt(KEY_KEEP_RUNS, DEF_KEEP_RUNS); }
    public void keepRuns(int v)  { sp.edit().putInt(KEY_KEEP_RUNS, v).apply(); }

    public boolean blackboxOn()  { return sp.getBoolean(KEY_BLACKBOX_ON, false); }
    public void blackboxOn(boolean v) { sp.edit().putBoolean(KEY_BLACKBOX_ON, v).apply(); }

    public int blackboxMinutes() { return sp.getInt(KEY_BLACKBOX_MIN, DEF_BLACKBOX_MIN); }
    public void blackboxMinutes(int v) { sp.edit().putInt(KEY_BLACKBOX_MIN, v).apply(); }

    public int blackboxBufferKb() { return sp.getInt(KEY_BLACKBOX_BUF, DEF_BLACKBOX_BUF); }
    public void blackboxBufferKb(int v) { sp.edit().putInt(KEY_BLACKBOX_BUF, v).apply(); }

    public boolean autoBoot()    { return sp.getBoolean(KEY_AUTO_BOOT, false); }
    public void autoBoot(boolean v) { sp.edit().putBoolean(KEY_AUTO_BOOT, v).apply(); }

    public boolean autoCrash()   { return sp.getBoolean(KEY_AUTO_CRASH, true); }
    public void autoCrash(boolean v) { sp.edit().putBoolean(KEY_AUTO_CRASH, v).apply(); }

    public boolean autoAnr()     { return sp.getBoolean(KEY_AUTO_ANR, true); }
    public void autoAnr(boolean v) { sp.edit().putBoolean(KEY_AUTO_ANR, v).apply(); }

    public boolean timerOn()     { return sp.getBoolean(KEY_TIMER_ON, false); }
    public void timerOn(boolean v) { sp.edit().putBoolean(KEY_TIMER_ON, v).apply(); }

    public int timerMinutes()    { return sp.getInt(KEY_TIMER_MIN, DEF_TIMER_MIN); }
    public void timerMinutes(int v) { sp.edit().putInt(KEY_TIMER_MIN, v).apply(); }

    public int timerKeep()       { return sp.getInt(KEY_TIMER_KEEP, DEF_TIMER_KEEP); }
    public void timerKeep(int v) { sp.edit().putInt(KEY_TIMER_KEEP, v).apply(); }

    public String uploadUrl()    { return sp.getString(KEY_UPLOAD_URL, ""); }
    public void uploadUrl(String v) { sp.edit().putString(KEY_UPLOAD_URL, v == null ? "" : v.trim()).apply(); }

    public String uploadToken()  { return sp.getString(KEY_UPLOAD_TOKEN, ""); }
    public void uploadToken(String v) { sp.edit().putString(KEY_UPLOAD_TOKEN, v == null ? "" : v.trim()).apply(); }

    public boolean autoUpload()  { return sp.getBoolean(KEY_AUTO_UPLOAD, false); }
    public void autoUpload(boolean v) { sp.edit().putBoolean(KEY_AUTO_UPLOAD, v).apply(); }

    public boolean dmesgBlocked() { return sp.getBoolean(KEY_DMESG_BLOCKED, false); }
    public String  dmesgBlockedReason() { return sp.getString(KEY_DMESG_REASON, ""); }
    public long    dmesgBlockedAt() { return sp.getLong(KEY_DMESG_AT, 0L); }

    /** Records that dmesg is unusable, so future runs never spawn it again. */
    public void dmesgBlocked(boolean blocked, String reason, long at) {
        sp.edit()
          .putBoolean(KEY_DMESG_BLOCKED, blocked)
          .putString(KEY_DMESG_REASON, reason == null ? "" : reason)
          .putLong(KEY_DMESG_AT, at)
          .apply();
    }

    /** Forgets the cached verdict so one fresh probe may run (user-initiated only). */
    public void clearDmesgVerdict() {
        sp.edit().remove(KEY_DMESG_BLOCKED).remove(KEY_DMESG_REASON).remove(KEY_DMESG_AT).apply();
    }

    public SharedPreferences raw() { return sp; }
}
