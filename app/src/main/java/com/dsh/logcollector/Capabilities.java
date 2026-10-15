package com.dsh.logcollector;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Probes what this app is actually allowed to do on this device, so the UI can tell the user
 * exactly which capability is missing and which command fixes it.
 */
public final class Capabilities {

    public final boolean root;
    public final String  rootDetail;

    public final boolean readLogsGranted;
    public final boolean dumpGranted;
    public final boolean allFilesGranted;
    public final boolean notificationsGranted;

    public final boolean canWriteTargetDirect;
    public final int     writeTier;          // see Storage.TIER_*
    public final String  writeDetail;

    public final boolean dmesgReadable;
    public final String  dmesgDetail;
    /** True when seccomp (not permissions) is what blocks dmesg - no grant can fix it. */
    public final boolean dmesgSeccompBlocked;
    public final int     dmesgExitCode;

    public final boolean screenrecordPresent;
    public final int     dumpsysServiceCount;

    // device facts
    public final String deviceModel;
    public final String androidRelease;
    public final int    sdkInt;
    public final String buildType;
    public final String buildFingerprint;
    public final String buildTags;
    public final boolean isDebuggable;

    private Capabilities(Builder b) {
        root = b.root; rootDetail = b.rootDetail;
        readLogsGranted = b.readLogsGranted; dumpGranted = b.dumpGranted;
        allFilesGranted = b.allFilesGranted; notificationsGranted = b.notificationsGranted;
        canWriteTargetDirect = b.canWriteTargetDirect; writeTier = b.writeTier; writeDetail = b.writeDetail;
        dmesgReadable = b.dmesgReadable; dmesgDetail = b.dmesgDetail;
        dmesgSeccompBlocked = b.dmesgSeccompBlocked; dmesgExitCode = b.dmesgExitCode;
        screenrecordPresent = b.screenrecordPresent; dumpsysServiceCount = b.dumpsysServiceCount;
        deviceModel = b.deviceModel; androidRelease = b.androidRelease; sdkInt = b.sdkInt;
        buildType = b.buildType; buildFingerprint = b.buildFingerprint; buildTags = b.buildTags;
        isDebuggable = b.isDebuggable;
    }

    private static final class Builder {
        boolean root; String rootDetail = "";
        boolean readLogsGranted, dumpGranted, allFilesGranted, notificationsGranted;
        boolean canWriteTargetDirect; int writeTier; String writeDetail = "";
        boolean dmesgReadable; String dmesgDetail = "";
        boolean dmesgSeccompBlocked; int dmesgExitCode = -1;
        boolean screenrecordPresent; int dumpsysServiceCount;
        String deviceModel = "", androidRelease = "", buildType = "", buildFingerprint = "", buildTags = "";
        int sdkInt; boolean isDebuggable;
    }

    /** Full probe. Slow-ish (spawns a few shell commands) so call it off the main thread. */
    public static Capabilities probe(Context ctx, String targetDir) {
        Builder b = new Builder();
        PackageManager pm = ctx.getPackageManager();
        String pkg = ctx.getPackageName();

        // Restore the persisted dmesg verdict before anything probes it.
        Prefs prefs = Prefs.get(ctx);
        DmesgGate.loadCached(prefs);

        b.sdkInt = Build.VERSION.SDK_INT;
        b.deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
        b.androidRelease = Build.VERSION.RELEASE;
        b.buildFingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
        b.buildTags = Build.TAGS == null ? "" : Build.TAGS;
        b.buildType = prop("ro.build.type");
        b.isDebuggable = (ctx.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;

        // ---------------------------------------------------------- permissions
        b.readLogsGranted = granted(ctx, Manifest.permission.READ_LOGS);
        b.dumpGranted = granted(ctx, Manifest.permission.DUMP);
        b.notificationsGranted = Build.VERSION.SDK_INT < 33 || granted(ctx, "android.permission.POST_NOTIFICATIONS");

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                b.allFilesGranted = Environment.isExternalStorageManager();
            } catch (Throwable t) {
                b.allFilesGranted = false;
            }
        } else {
            b.allFilesGranted = granted(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        // ---------------------------------------------------------------- root
        b.root = Shell.hasRoot();
        b.rootDetail = Shell.rootDiagnosis();

        // ------------------------------------------------------------- writing
        Storage.Probe wp = Storage.probeWrite(ctx, targetDir);
        b.canWriteTargetDirect = wp.direct;
        b.writeTier = wp.tier;
        b.writeDetail = wp.detail;

        // --------------------------------------------------------------- dmesg
        // The exit status MUST come from `dmesg` itself, never from a pipeline: piping into
        // `head` makes the pipeline succeed even when dmesg was killed outright, which is exactly
        // how a SIGSYS (seccomp) kill used to be mis-reported as "dmesg readable".
        DmesgProbe dp = probeDmesg();
        b.dmesgReadable = dp.readable;
        b.dmesgDetail = dp.detail;
        b.dmesgSeccompBlocked = dp.seccompBlocked;
        b.dmesgExitCode = dp.exitCode;

        // Arm and persist the verdict. This must happen even for a seccomp *prediction*, not only
        // for a spawned-and-killed probe: Collector.run() calls this probe before it collects
        // anything, and if the gate is left unarmed the very next step (collectDmesg) spawns a
        // real dmesg and writes the tombstone we were trying to avoid.
        if (dp.seccompBlocked) {
            DmesgGate.markBlocked(prefs, dp.detail);
        } else if (dp.readable) {
            DmesgGate.markReadable(prefs);
        }

        // ---------------------------------------------------------- screenrecord
        Shell.Result sr = Shell.run("test -x /system/bin/screenrecord && echo YES || echo NO", 8000);
        b.screenrecordPresent = sr.out.contains("YES");

        // ------------------------------------------------------- dumpsys services
        Shell.Result ds = Shell.run("dumpsys -l 2>/dev/null | wc -l", 15000);
        b.dumpsysServiceCount = parseIntSafe(ds.out.trim(), 0);

        return new Capabilities(b);
    }

    /** What a real {@code dmesg} attempt actually did. */
    public static final class DmesgProbe {
        public boolean readable;
        public String  detail = "";
        /** True when the kernel's seccomp filter killed the call - no permission grants help. */
        public boolean seccompBlocked;
        /** True when this result came from the cached verdict and no process was spawned. */
        public boolean skipped;
        public int     exitCode;
    }

    /**
     * Runs {@code dmesg} and reports the truth about it.
     *
     * <p>Three distinct failure modes have to be told apart, because the remedy is different for
     * each:
     * <ul>
     *   <li><b>killed by SIGSYS</b> (exit 128+31 = 159) - the app's seccomp filter forbids the
     *       {@code syslog} syscall from an untrusted_app domain. No permission can fix this; the
     *       kernel ring buffer is simply unavailable to this process.</li>
     *   <li><b>exit 1 with "Operation not permitted" / "Permission denied"</b> - DAC or SELinux
     *       refused it. {@code adb root} or the {@code shell} uid can still get it.</li>
     *   <li><b>exit 0 with output</b> - genuinely readable.</li>
     * </ul>
     *
     * <p><b>Before spawning anything</b>, {@link #seccompPredictsDmesgBlocked()} is consulted: a
     * seccomp-filtered process domain cannot call {@code syslog}, and finding that out by trying
     * costs a tombstone (see {@link DmesgGate}). The prediction is free and avoids the test
     * entirely on the devices where it matters most.
     */
    public static DmesgProbe probeDmesg() {
        DmesgProbe p = new DmesgProbe();

        // If a previous probe already proved dmesg is unusable, do NOT try again: every doomed
        // attempt makes the kernel write a tombstone, and /data/tombstones is a ~32 entry ring
        // that holds real native-crash evidence. See DmesgGate.
        if (DmesgGate.isBlocked()) {
            p.readable = false;
            p.seccompBlocked = true;
            p.exitCode = 159;
            p.skipped = true;
            // Bare reason only - DmesgGate strips labels from anything it is handed, but not
            // adding one here keeps the display and the stored value on the same footing.
            p.detail = DmesgGate.reason();
            return p;
        }

        // Free pre-check: if this process is under a seccomp filter, syslog is denied by policy
        // and spawning dmesg would only manufacture a tombstone.
        //
        // Only meaningful when we would actually run dmesg *as this app*. With working root, the
        // command goes through `su`, which the magisk/root daemon forks into its own unrestricted
        // domain - that process does NOT inherit our seccomp filter, so dmesg can legitimately
        // work there. Predicting a block in that case would break a path that actually functions.
        if (!Shell.hasRoot() && seccompPredictsDmesgBlocked()) {
            p.readable = false;
            p.seccompBlocked = true;
            p.exitCode = 159;
            p.skipped = true;
            p.detail = "dmesg BLOCKED - this process runs under a seccomp filter "
                     + "(Seccomp: 2 in /proc/self/status), and the app security domain's filter "
                     + "denies the syslog syscall. Predicted without spawning dmesg, so no "
                     + "tombstone was written. Use adb to read the kernel log.";
            return p;
        }

        Shell.Result r = Shell.run("dmesg >/dev/null 2>&1; echo \"__DSHEXIT=$?\"", 15000);
        p.exitCode = extractExit(r.out);

        if (p.exitCode == 0) {
            // Confirm it actually produced data rather than an empty ring buffer.
            Shell.Result v = Shell.run("dmesg 2>/dev/null | wc -l", 20000);
            long lines = parseLongSafe(v.out.trim(), 0);
            p.readable = lines > 0;
            p.detail = p.readable
                    ? ("dmesg readable via " + r.modeName() + " (" + lines + " lines)")
                    : ("dmesg returned no data (empty ring buffer?) via " + r.modeName());
            return p;
        }
        // Capture the diagnostic text for the failing cases.
        Shell.Result t = Shell.run("dmesg 2>&1 | head -n 3", 15000);
        String text = (t.out + " " + t.err).trim();

        boolean killed = p.exitCode > 128;
        int signal = killed ? p.exitCode - 128 : 0;
        p.seccompBlocked = killed && signal == 31;   // SIGSYS

        String why;
        if (p.seccompBlocked) {
            why = "kernel seccomp filter killed the syslog syscall (SIGSYS). "
                + "No permission grant can work around this - the ring buffer is not exposed "
                + "to the app's security domain at all.";
        } else if (killed) {
            why = "dmesg was killed by signal " + signal + ". "
                + (text.length() > 0 ? text : "No message was printed.");
        } else if (text.contains("not permitted") || text.contains("Permission denied")
                || text.contains("Operation not permitted")) {
            why = "refused by the kernel: " + Util.trimTo(text, 200);
        } else {
            why = "exit=" + p.exitCode + (text.length() > 0 ? (" :: " + Util.trimTo(text, 200))
                                                             : " (no output)");
        }
        p.readable = false;
        p.detail = "dmesg BLOCKED - " + why;
        return p;
    }

    /**
     * Predicts, without spawning anything, that {@code dmesg} will be killed by seccomp.
     *
     * <p>Reads {@code Seccomp:} from this process's own {@code /proc/self/status}. A value of
     * {@code 2} means a seccomp filter is installed, and in every Android app security domain
     * that filter denies the {@code syslog} syscall - so {@code dmesg} is guaranteed to die with
     * SIGSYS. Reading a procfs file costs nothing and, crucially, writes no tombstone.
     *
     * <p>Measured on the test device: our app reported {@code Seccomp: 2} while the adb shell
     * domain reported {@code Seccomp: 0} - which is exactly why {@code adb shell dmesg} works and
     * the app's does not.
     *
     * <p>This is a prediction, not a proof: if the filter happens to permit syslog, the caller
     * still falls through to a real probe and gets the truth.
     */
    static boolean seccompPredictsDmesgBlocked() {
        try {
            String status = Util.readFile(new java.io.File("/proc/self/status"));
            if (status == null) return false;
            String[] lines = status.split("\n");
            for (String line : lines) {
                if (line.startsWith("Seccomp:")) {
                    String v = line.substring("Seccomp:".length()).trim();
                    // 2 = SECCOMP_MODE_FILTER, 1 = SECCOMP_MODE_STRICT; both deny syslog.
                    return "1".equals(v) || "2".equals(v);
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static int extractExit(String out) {
        if (out == null) return -1;
        int i = out.lastIndexOf("__DSHEXIT=");
        if (i < 0) return -1;
        String tail = out.substring(i + "__DSHEXIT=".length()).trim();
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < tail.length(); k++) {
            char c = tail.charAt(k);
            if (c >= '0' && c <= '9') sb.append(c); else break;
        }
        return sb.length() == 0 ? -1 : parseIntSafe(sb.toString(), -1);
    }

    private static long parseLongSafe(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Throwable t) { return def; }
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }

    private static boolean granted(Context ctx, String perm) {
        try {
            return ctx.checkPermission(perm, android.os.Process.myPid(), android.os.Process.myUid())
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String prop(String key) {
        Shell.Result r = Shell.app("getprop " + key, 6000);
        return r.out.trim();
    }

    /** appops-based check, used as a second opinion for READ_LOGS / DUMP on some builds. */
    public static boolean appOpsAllowed(Context ctx, String op) {
        try {
            AppOpsManager aom = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return false;
            java.lang.reflect.Method m = AppOpsManager.class.getMethod(
                    "checkOpNoThrow", String.class, int.class, String.class);
            Object r = m.invoke(aom, op, android.os.Process.myUid(), ctx.getPackageName());
            return r instanceof Integer && ((Integer) r) == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ report

    /** Human-readable capability report, written into every collection run as capability.txt. */
    public String report(String targetDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("DSH LogCollector - capability report\n");
        sb.append("generated : ").append(Util.human()).append("\n");
        sb.append("target dir: ").append(targetDir).append("\n");
        sb.append("\n");
        sb.append("--- device ---------------------------------------------------------\n");
        sb.append("model      : ").append(deviceModel).append("\n");
        sb.append("android    : ").append(androidRelease).append(" (API ").append(sdkInt).append(")\n");
        sb.append("build.type : ").append(emptyDash(buildType)).append("\n");
        sb.append("build.tags : ").append(emptyDash(buildTags)).append("\n");
        sb.append("fingerprint: ").append(emptyDash(buildFingerprint)).append("\n");
        sb.append("app debug  : ").append(isDebuggable).append("\n");
        sb.append("\n");
        sb.append("--- privileges -----------------------------------------------------\n");
        sb.append("root (su)          : ").append(root ? "YES" : "NO").append("  - ").append(rootDetail).append("\n");
        sb.append("READ_LOGS          : ").append(readLogsGranted ? "granted" : "DENIED").append("\n");
        sb.append("DUMP               : ").append(dumpGranted ? "granted" : "DENIED").append("\n");
        sb.append("all-files access   : ").append(allFilesGranted ? "granted" : "DENIED").append("\n");
        sb.append("POST_NOTIFICATIONS : ").append(notificationsGranted ? "granted" : "DENIED").append("\n");
        sb.append("\n");
        sb.append("--- collection ability ---------------------------------------------\n");
        sb.append("dmesg              : ").append(dmesgReadable ? "READABLE" : "BLOCKED");
        if (!dmesgReadable && dmesgSeccompBlocked) sb.append("  (seccomp)");
        sb.append("\n");
        sb.append("                     ").append(dmesgDetail).append("\n");
        sb.append("dumpsys services   : ").append(dumpsysServiceCount > 0 ? (dumpsysServiceCount + " listed by `dumpsys -l`") : "could not list").append("\n");
        sb.append("screenrecord binary: ").append(screenrecordPresent ? "present" : "MISSING").append("\n");
        sb.append("write to target    : ").append(writeTierName(writeTier)).append("\n");
        sb.append("                     ").append(writeDetail).append("\n");
        sb.append("screenshot via app : ").append(sdkInt >= 21 ? "yes (MediaProjection)" : "no").append("\n");
        sb.append("\n");
        sb.append("--- what to do about denied items ----------------------------------\n");
        sb.append("Note: `dumpsys <service>` can be denied for a service-specific reason even\n");
        sb.append("when the DUMP permission IS granted. See each file under dumpsys/ for the\n");
        sb.append("exact reason, and index.txt for the per-service outcome.\n");
        if (!readLogsGranted) {
            sb.append("* READ_LOGS denied -> logcat only shows this app's own messages.\n");
            sb.append("  userdebug/eng build:  adb shell pm grant ").append("com.dsh.logcollector")
              .append(" android.permission.READ_LOGS\n");
        } else {
            sb.append("* READ_LOGS is granted - full logcat is being captured.\n");
        }
        if (!dumpGranted) {
            sb.append("* DUMP denied -> `dumpsys <service>` returns permission errors.\n");
            sb.append("  userdebug/eng build:  adb shell pm grant com.dsh.logcollector android.permission.DUMP\n");
        } else {
            sb.append("* DUMP is granted. Services that still refuse need a *different* permission;\n");
            sb.append("  the common ones are listed below.\n");
        }
        sb.append("  On a userdebug build these unlock the ~18 services that refuse with\n");
        sb.append("  \"missing android.permission.PACKAGE_USAGE_STATS\" even when DUMP is granted.\n");
        sb.append("  The appops line is the one that works without a manifest declaration:\n");
        sb.append("    adb shell appops set --uid com.dsh.logcollector GET_USAGE_STATS allow\n");
        sb.append("    adb shell pm grant com.dsh.logcollector android.permission.PACKAGE_USAGE_STATS\n");
        sb.append("    adb shell pm grant com.dsh.logcollector android.permission.BATTERY_STATS\n");
        sb.append("  (the latter two only work because this app declares those permissions)\n");
        sb.append("  Or run the whole collection from a root shell:\n");
        sb.append("    adb root && adb shell am start-foreground-service -n com.dsh.logcollector/.CollectorService -a com.dsh.logcollector.COLLECT_NOW\n");
        if (!dmesgReadable) {
            sb.append("* dmesg is blocked");
            if (dmesgSeccompBlocked) {
                sb.append(" by the kernel's seccomp filter (SIGSYS).\n");
                sb.append("  This is NOT a permission problem: no `pm grant` and no root helper inside\n");
                sb.append("  the app can bypass it, because the syslog syscall is removed from the\n");
                sb.append("  app's security domain. Get dmesg from adb instead:\n");
                sb.append("    adb root && adb shell dmesg > dmesg.txt\n");
                sb.append("  (the kernel/ directory of every run still holds all readable /proc data)\n");
            } else {
                sb.append(" by DAC or SELinux (exit=").append(dmesgExitCode).append(").\n");
                sb.append("  Try: adb root && adb shell dmesg > dmesg.txt\n");
                sb.append("  Or, on a userdebug build, run the collection from a root shell so the\n");
                sb.append("  app's own `su` path is used.\n");
            }
        }
        if (writeTier == Storage.TIER_APP_PRIVATE) {
            sb.append("* Cannot write ").append(targetDir).append(" directly.\n");
            sb.append("  Fix: Settings -> Apps -> DSH LogCollector -> allow 'All files access'\n");
            sb.append("  or:  adb shell appops set --uid com.dsh.logcollector MANAGE_EXTERNAL_STORAGE allow\n");
        } else {
            sb.append("* Writing to ").append(targetDir).append(" works (").append(writeTierName(writeTier)).append(").\n");
        }
        if (readLogsGranted && dumpGranted && dmesgReadable
                && writeTier != Storage.TIER_APP_PRIVATE) {
            sb.append("* nothing missing - full-fidelity collection is available.\n");
        }
        return sb.toString();
    }

    public static String writeTierName(int tier) {
        switch (tier) {
            case Storage.TIER_DIRECT: return "direct file write (app has access)";
            case Storage.TIER_ROOT_COPY: return "staged locally, copied into place via su";
            case Storage.TIER_SHELL: return "written through a shell redirect";
            default: return "FALLBACK: app-private directory only";
        }
    }

    private static String emptyDash(String s) { return s == null || s.length() == 0 ? "-" : s; }

    /** Short lines for the on-screen capability panel. */
    public List<String[]> summaryRows(Context ctx, String targetDir) {
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[] { "Root (su)", root ? "YES" : "no",
                root ? rootDetail
                     : "adb root 只让 adb shell 变成 root，不会给应用任何权限；应用要 root 需 Magisk/SuperSU 类 su" });
        rows.add(new String[] { "READ_LOGS", readLogsGranted ? "granted" : "DENIED",
                readLogsGranted ? "" : "adb shell pm grant " + ctx.getPackageName() + " android.permission.READ_LOGS" });
        rows.add(new String[] { "DUMP", dumpGranted ? "granted" : "DENIED",
                dumpGranted ? "" : "adb shell pm grant " + ctx.getPackageName() + " android.permission.DUMP" });
        rows.add(new String[] { "All files", allFilesGranted ? "granted" : "DENIED",
                allFilesGranted ? "" : "Settings -> All files access" });
        rows.add(new String[] { "dmesg", dmesgReadable ? "readable" : "BLOCKED",
                dmesgReadable ? "" : "needs root, or adb shell dmesg > file" });
        rows.add(new String[] { "dumpsys", dumpsysServiceCount + " services", "" });
        rows.add(new String[] { "screenrecord", screenrecordPresent ? "found" : "missing", "" });
        rows.add(new String[] { "Write target", writeTierName(writeTier), writeDetail });
        return rows;
    }

    /** True when we should offer the user a trip to the "All files access" settings page. */
    public boolean needsAllFilesRequest() {
        return sdkInt >= 30 && !allFilesGranted && !root;
    }

    public boolean needsNotificationRequest() {
        return sdkInt >= 33 && !notificationsGranted;
    }

    public String settingsAllFilesAction() {
        return Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION;
    }

    public File targetDirFile(String targetDir) { return new File(targetDir); }
}
