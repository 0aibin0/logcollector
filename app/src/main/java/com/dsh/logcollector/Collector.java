package com.dsh.logcollector;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The collection engine. One {@link #run} call produces one timestamped directory under the
 * configured base path containing every artefact listed in {@link Options}.
 *
 * <p>Every step is individually timed, individually timed out, and individually recorded in
 * {@code index.txt}, so a partially degraded device still produces a useful, honest run.
 */
public final class Collector {

    private Collector() { }

    // -------------------------------------------------------------- progress

    public interface Progress {
        /** @param phase short label for the UI; @param detail what is happening right now */
        void onStep(String phase, String detail);
        void onDone(Report report);
    }

    /** No-op progress sink. */
    public static final Progress SILENT = new Progress() {
        public void onStep(String phase, String detail) { }
        public void onDone(Report report) { }
    };

    // -------------------------------------------------------------- options

    public static final class Options {
        public String  baseDir       = Prefs.DEF_BASE_DIR;
        public boolean doDmesg       = true;
        public boolean doLogcat      = true;
        public boolean logBuffers    = true;
        public int     logTailKb     = Prefs.DEF_LOG_TAIL_KB;
        public int     dmesgTailKb   = Prefs.DEF_DMESG_TAIL_KB;
        public boolean doDumpsys     = true;
        public boolean dumpsysFull   = true;
        public boolean dumpsysExtra  = true;
        public boolean doProp        = true;
        public boolean doKernel      = true;
        public boolean doDisplay     = true;
        public boolean doCmdline     = true;
        public boolean doScreenshot  = true;
        public boolean doRecord      = false;
        public int     recordSeconds = Prefs.DEF_RECORD_SEC;
        public boolean doBugreport   = false;
        public int     keepRuns      = Prefs.DEF_KEEP_RUNS;
        public String  namePrefix    = "";

        /** Overall wall-clock budget for the whole run; the collector stops starting new work past it. */
        public long    budgetMs      = 20 * 60 * 1000L;

        public static Options from(Context ctx) {
            Prefs p = Prefs.get(ctx);
            Options o = new Options();
            o.baseDir       = p.baseDir();
            o.logBuffers    = p.logBuffers();
            o.logTailKb     = p.logTailKb();
            o.dmesgTailKb   = p.dmesgTailKb();
            o.dumpsysFull   = p.dumpsysFull();
            o.dumpsysExtra  = p.dumpsysExtra();
            o.doProp        = p.doProp();
            o.doKernel      = p.doKernel();
            o.doDisplay     = p.doDisplay();
            o.doCmdline     = p.doCmdline();
            o.doScreenshot  = p.doScreenshot();
            o.doRecord      = p.doRecord();
            o.recordSeconds = p.recordSeconds();
            o.doBugreport   = p.doBugreport();
            o.keepRuns      = p.keepRuns();
            return o;
        }
    }

    // --------------------------------------------------------------- report

    public static final class Report {
        public boolean ok;
        public String  dir = "";
        public String  requestedDir = "";
        public String  error = "";
        public long    ms;
        public int     files;
        public long    bytes;
        public int     tier = Storage.TIER_DIRECT;
        public String  tierDetail = "";
        public String  zipPath = "";
        public String  uploadResult = "";
        public String  latestResult = "";
        public int     pruned;
        public String  indexText = "";
        /** Set by {@link Verifier}: how many artefacts are OK, and how many need attention. */
        public boolean verified;
        public int     okCount;
        public int     gapCount;
        /** True when the run completed but had to leave gaps (see verify.txt). */
        public boolean partial;
        /**
         * Artefacts that were attempted but deliberately not produced, with the reason.
         *
         * <p>Needed because {@link Verifier} can only inspect files that exist: an artefact a
         * collector decided not to write is invisible to it, so the self-check could report GOOD
         * while half the collection was absent. Collectors register their gaps here instead.
         */
        public final List<String[]> gaps = new ArrayList<String[]>();
        public final List<String> warnings = new ArrayList<String>();

        public String shortSummary() {
            if (!ok) return "采集失败: " + error;
            return "采集完成: " + files + " 个文件, " + Util.bytes(bytes) + " -> " + dir;
        }
    }

    // ------------------------------------------------------------------- run

    public static Report run(Context ctx, Options o, Progress cb) {
        final long t0 = System.currentTimeMillis();
        final Report rep = new Report();
        final StringBuilder index = new StringBuilder();
        final List<String> warnings = rep.warnings;

        index.append("DSH LogCollector - collection index\n");
        index.append("started  : ").append(Util.human()).append("\n");
        index.append("requested: ").append(o.baseDir).append("\n");
        index.append("\n");
        index.append("Legend: each line is '<artifact> :: <status>'. 'mode' is how the command ran\n");
        index.append("        (root = via su, app = as this app's uid).\n");
        index.append("\n");

        step(cb, "准备", "权限与存储探测…");

        Capabilities caps = Capabilities.probe(ctx, o.baseDir);

        // ------------------------------------------------------- begin session
        Storage.Session s = Storage.begin(ctx, o.baseDir, o.namePrefix);
        rep.dir = s.dir.getAbsolutePath();
        rep.requestedDir = s.requestedBase;
        rep.tier = s.tier;
        rep.tierDetail = s.tierDetail;

        if (s.tier == Storage.TIER_APP_PRIVATE) {
            warnings.add("无法写入 " + o.baseDir + "，已回退到应用私有目录 " + s.base
                    + "。请授予「所有文件访问权限」或以 root 运行。");
        } else if (s.tier != Storage.TIER_DIRECT) {
            warnings.add("目标目录需要提权写入：" + s.tierDetail);
        }

        index.append("run dir  : ").append(rep.dir).append("\n");
        index.append("write mode: ").append(Capabilities.writeTierName(s.tier)).append("\n");
        index.append("           ").append(s.tierDetail).append("\n");
        index.append("\n");

        // capability report always lands first - it explains everything below it
        try {
            Storage.WriteResult w = Storage.writeText(ctx, s, "capability.txt", caps.report(o.baseDir));
            index.append("capability.txt :: ").append(w.ok ? "ok" : ("FAILED: " + w.detail)).append("\n");
        } catch (Throwable t) {
            index.append("capability.txt :: EXCEPTION ").append(t).append("\n");
        }

        final long deadline = t0 + o.budgetMs;

        // ------------------------------------------------------------- dmesg
        if (o.doDmesg) {
            step(cb, "dmesg", "读取内核环形缓冲区…");
            index.append("\n--- dmesg ---\n");
            collectDmesg(ctx, s, o, index, warnings);
        }

        // ------------------------------------------------------------ logcat
        if (o.doLogcat) {
            step(cb, "logcat", "导出各日志缓冲区…");
            index.append("\n--- logcat ---\n");
            collectLogcat(ctx, s, o, index, warnings, cb);
        }

        // ------------------------------------------------------------ dumpsys
        if (o.doDumpsys) {
            index.append("\n--- dumpsys ---\n");
            collectDumpsys(ctx, s, o, index, warnings, cb, deadline, rep);
        }

        // --------------------------------------------------------------- prop
        if (o.doProp) {
            step(cb, "系统属性", "getprop…");
            index.append("\n--- properties ---\n");
            collectProp(ctx, s, index);
        }

        // ------------------------------------------------------------- cmdline
        if (o.doCmdline) {
            step(cb, "启动参数", "内核 cmdline / boot args…");
            index.append("\n--- cmdline / boot args ---\n");
            collectCmdline(ctx, s, index, warnings, caps);
        }

        // ------------------------------------------------------------- kernel
        if (o.doKernel) {
            step(cb, "内核专项", "/proc 与 /sys 快照…");
            index.append("\n--- kernel ---\n");
            collectKernel(ctx, s, index, warnings, cb);
        }

        // ------------------------------------------------------------ display
        if (o.doDisplay) {
            step(cb, "显示/背光", "面板与亮度快照…");
            index.append("\n--- display / backlight ---\n");
            DisplayCollector.collect(ctx, s, index, caps);
        }

        // -------------------------------------------------------------- media
        index.append("\n--- media ---\n");
        if (o.doScreenshot && System.currentTimeMillis() < deadline) {
            step(cb, "截屏", "抓取当前画面…");
            MediaCollector.screenshot(ctx, s, index, warnings);
        }
        if (o.doRecord && System.currentTimeMillis() < deadline) {
            step(cb, "录屏", "录制 " + o.recordSeconds + " 秒…");
            MediaCollector.screenRecord(ctx, s, index, warnings, o.recordSeconds, cb);
        }

        // ---------------------------------------------------------- bugreport
        if (o.doBugreport && System.currentTimeMillis() < deadline) {
            step(cb, "bugreport", "整机 bugreport（可能耗时数分钟）…");
            index.append("\n--- bugreport ---\n");
            collectBugreport(ctx, s, index, warnings, deadline - System.currentTimeMillis());
        } else if (o.doBugreport) {
            index.append("bugreport :: SKIPPED (预算耗尽)\n");
            warnings.add("时间预算用尽，已跳过 bugreport。");
        }

        // ------------------------------------------------------------ finalise
        step(cb, "收尾", "自检与 latest 指针…");

        // Self-check BEFORE the summary, so index.txt can report the verified tallies and the
        // file count includes verify.txt itself.
        try {
            Verifier.verify(ctx, s, rep);
        } catch (Throwable t) {
            warnings.add("自检失败: " + t);
        }

        long files = Util.countFiles(s.dir);
        long bytes = Util.dirSize(s.dir);
        rep.files = (int) files;
        rep.bytes = bytes;

        index.append("\n--- summary ---\n");
        index.append("finished : ").append(Util.human()).append("\n");
        index.append("elapsed  : ").append((System.currentTimeMillis() - t0) / 1000.0).append(" s\n");
        index.append("files    : ").append(files).append("\n");
        index.append("bytes    : ").append(bytes).append(" (").append(Util.bytes(bytes)).append(")\n");
        if (rep.verified) {
            index.append("self-check : ").append(rep.okCount).append(" artefact(s) OK, ")
                 .append(rep.gapCount).append(" need attention")
                 .append(rep.gapCount == 0 ? "" : " - see verify.txt for the list and how to fix")
                 .append("\n");
        }
        if (!warnings.isEmpty()) {
            index.append("\n--- warnings ---\n");
            for (String w : warnings) index.append("* ").append(w).append("\n");
        }
        index.append("\n--- where to start reading -----------------------------------------\n");
        index.append("verify.txt        : did this run capture what you need? gaps + fixes\n");
        index.append("capability.txt    : what this device allows and what it denies\n");
        index.append("logcat_all.txt    : every logcat buffer merged, chronological\n");
        index.append("kernel_log.txt    : kernel messages (logcat -b kernel; dmesg is often denied)\n");
        index.append("cmdline.txt       : kernel command line / boot args / panel parameters\n");
        index.append("display/          : panel, backlight and brightness detail\n");
        index.append("index.txt         : this file - per-artefact status and read mode\n");

        Storage.WriteResult iw = Storage.writeText(ctx, s, "index.txt", index.toString());
        if (!iw.ok) {
            rep.warnings.add("index.txt 写入失败: " + iw.detail);
        }
        rep.indexText = index.toString();

        // latest pointer
        try {
            rep.latestResult = Storage.updateLatest(ctx, s);
        } catch (Throwable t) {
            rep.latestResult = "failed: " + t;
        }

        // retention
        try {
            rep.pruned = Storage.prune(ctx, s.requestedBase, o.keepRuns);
        } catch (Throwable ignored) { }

        rep.ms = System.currentTimeMillis() - t0;
        // Success means "the run completed", not "the run collected everything". The two are
        // different on a locked-down device, where a run can finish with very little. Keeping
        // them distinct matters because the UI reports one and verify.txt reports the other:
        // claiming success unconditionally made a content-free run look fine.
        rep.ok = true;
        if (rep.verified && rep.gapCount > 0) {
            rep.partial = true;
        }

        step(cb, "完成", rep.files + " 个文件 / " + Util.bytes(rep.bytes));
        cb.onDone(rep);
        return rep;
    }

    /** Writes the dmesg placeholder without ever spawning a dmesg process. */
    private static void writeDmesgUnavailable(Context ctx, Storage.Session s, StringBuilder index,
                                              List<String> warnings, String shortStatus, String detail) {
        String note = "dmesg NOT AVAILABLE\n"
                + "===================\n\n"
                + seccompExplanation(-1) + "\n"
                + "detail: " + (detail == null ? "" : detail) + "\n\n"
                + "IMPORTANT: this app will not retry dmesg on this device.\n"
                + "Each attempt would make the kernel write a tombstone into\n"
                + "/data/tombstones/, and that ring buffer only holds ~32 entries -\n"
                + "retrying would overwrite genuine native-crash evidence.\n\n"
                + "Use the PC-side helper to fill this gap:\n"
                + "    pwsh -File tools/augment_from_pc.ps1\n"
                + "which writes dmesg_from_pc.txt into this same run directory.\n";
        Storage.WriteResult w = Storage.writeText(ctx, s, "dmesg.txt", note);
        index.append("dmesg.txt :: NOT AVAILABLE (").append(shortStatus).append(")")
             .append(w.ok ? "" : " [placeholder write also failed]")
             .append(" :: not retried, to protect /data/tombstones\n");
        warnings.add("dmesg 取不到（" + shortStatus + "）。已停止重试以保护 tombstone；"
                + "请用 tools/augment_from_pc.ps1 从电脑侧补齐。");
    }

    private static String seccompExplanation(int exitCode) {
        return "The kernel's seccomp filter kills the `dmesg` process with SIGSYS"
             + (exitCode > 0 ? (" (exit " + exitCode + " = 128 + 31)") : "") + ".\n\n"
             + "This is NOT a permission problem. The syslog syscall is removed from the\n"
             + "app's security domain, so no `pm grant`, no appops change and no root\n"
             + "helper launched from inside the app can bypass it. This is the normal\n"
             + "situation for an app on Android 11+.\n\n"
             + "Get the kernel log from adb instead - it always works:\n\n"
             + "    adb root\n"
             + "    adb shell dmesg > dmesg.txt\n\n"
             + "or, without root, on a userdebug build:\n\n"
             + "    adb shell dmesg > dmesg.txt\n";
    }

    /**
     * Kernel log via {@code logcat -b kernel} - the channel that works when {@code dmesg} cannot.
     *
     * <p>logd holds the same kernel ring buffer that {@code /dev/kmsg} exposes, so once
     * READ_LOGS is held this needs no root and no privileged syscall. Written as
     * {@code kernel_log.txt} so it is never mistaken for a real {@code dmesg} capture: the
     * timestamp format and the pid/tid columns come from logd, and the buffer is logd's
     * (typically 1 MiB) rather than the kernel's own.
     */
    private static void collectKernelLogcat(Context ctx, Storage.Session s, Options o,
                                            StringBuilder index, List<String> warnings) {
        int lines = Math.max(200, (o.dmesgTailKb * 1024) / 128);
        Shell.Result r = Shell.runReported(
                "logcat -b kernel -d -v threadtime -t " + lines, 60000);
        String body = r.out;
        if (body.length() == 0) {
            // Older builds reject -t; fall back to a plain dump trimmed with tail.
            Shell.Result r2 = Shell.runReported(
                    "logcat -b kernel -d -v threadtime | tail -n " + lines, 60000);
            body = r2.out;
        }

        if (body.length() == 0) {
            // Never leave a silent gap: write an explicit explanation so verify.txt and the
            // operator both see WHY the kernel log is absent, rather than finding no file.
            String why = "KERNEL LOG NOT AVAILABLE\n"
                    + "=======================\n\n"
                    + "`logcat -b kernel` returned no data on this device.\n\n"
                    + "This buffer is a logd feature; some builds do not expose it, and some\n"
                    + "vendor ROMs restrict it. It was WORKING on the moto g(100) / Android 12\n"
                    + "reference device, so its absence here is device specific.\n\n"
                    + "What to do next:\n"
                    + "  1. confirm the buffer list:  logcat -g   (see logcat_buffer_sizes.txt)\n"
                    + "  2. if a kernel buffer exists but is empty, the kernel may simply not be\n"
                    + "     logging to logd on this build.\n"
                    + "  3. read the kernel ring buffer directly instead - needs root:\n"
                    + "       pwsh -File tools/augment_from_pc.ps1     (writes dmesg_from_pc.txt)\n"
                    + "  4. kernel log levels are still partly visible in dmesg.txt (if present)\n"
                    + "     and in the kernel/ directory of this run.\n";
            Storage.WriteResult w = Storage.writeText(ctx, s, "kernel_log.txt", why);
            index.append("kernel_log.txt :: NOT AVAILABLE (logcat -b kernel empty)")
                 .append(w.ok ? " :: placeholder written" : (" :: placeholder write failed: " + w.detail))
                 .append("\n");
            warnings.add("内核日志取不到：dmesg 被 seccomp 拦截，logcat -b kernel 也为空。"
                    + "详见 kernel_log.txt 与 tools/augment_from_pc.ps1。");
            return;
        }

        String header =
                "# KERNEL LOG - captured via `logcat -b kernel`, not via `dmesg`.\n"
              + "#\n"
              + "# Why not dmesg: this app is denied the syslog syscall by the kernel's seccomp\n"
              + "# filter (and every attempt writes a tombstone into /data/tombstones, so it is\n"
              + "# not retried). logd republishes the same kernel ring buffer, which an app may\n"
              + "# read once READ_LOGS is granted - that is what you are looking at.\n"
              + "#\n"
              + "# Differences from a real dmesg capture:\n"
              + "#   * timestamps are wall-clock (MM-DD HH:MM:SS.mmm) from logd, not kernel uptime\n"
              + "#   * columns are 'pid tid priority tag: message' rather than '<level>[uptime]'\n"
              + "#   * capacity is logd's kernel buffer (see logcat_buffer_sizes.txt), not the\n"
              + "#     kernel's own log_buf_len, so very early boot messages may have rotated out\n"
              + "#\n"
              + "# read mode : " + r.modeName() + "\n"
              + "# collected : " + Util.human() + "\n"
              + "# lines     : " + lines + "\n"
              + "\n";
        Storage.WriteResult w = Storage.writeText(ctx, s, "kernel_log.txt", header + body);
        index.append("kernel_log.txt :: ").append(w.ok ? "ok" : ("FAILED: " + w.detail))
             .append(" :: ").append(Util.bytes(body.length()))
             .append(" (logcat -b kernel, NOT dmesg)\n");

        // Kernel-severity extracts are the reason people read dmesg in the first place.
        String[][] extracts = {
                { "kernel_log_errors.txt", "E ", "kernel errors" },
                { "kernel_log_warnings.txt", "W ", "kernel warnings" },
        };
        for (String[] e : extracts) {
            StringBuilder hits = new StringBuilder();
            int n = 0;
            for (String line : body.split("\n")) {
                // threadtime columns: date time pid tid PRIORITY tag: msg
                if (line.length() > 30 && line.charAt(19) == ' ' && line.contains(" " + e[1])) {
                    hits.append(line).append("\n");
                    n++;
                }
            }
            Storage.writeText(ctx, s, e[0],
                    "# " + e[2] + " extracted from logcat -b kernel\n# matched: " + n + "\n\n" + hits);
            index.append(e[0]).append(" :: ").append(n).append(" lines\n");
        }
    }

    /**
     * Boot arguments and the kernel command line.
     *
     * <p>Three sources, tried in order, because availability differs sharply by build:
     * <ol>
     *   <li><b>{@code /proc/cmdline}</b> - the real thing, but SELinux labels it
     *       {@code proc_cmdline} and denies it to both {@code shell} and {@code untrusted_app}
     *       on production builds. Only root can read it. Verified denied on the test device.</li>
     *   <li><b>{@code /proc/bootconfig}</b> - present on Android 12+ (GKI), usually world
     *       readable.</li>
     *   <li><b>{@code ro.boot.*} properties</b> - <i>always</i> readable, and they mirror the
     *       {@code androidboot.*} entries of the command line one-for-one. This is the fallback
     *       that makes the section useful on every device.</li>
     * </ol>
     * The {@code androidboot.*} to {@code ro.boot.*} mapping is what makes (3) worth having: e.g.
     * {@code androidboot.serialno=X} becomes {@code ro.boot.serialno=X}.
     */
    private static void collectCmdline(Context ctx, Storage.Session s,
                                       StringBuilder index, List<String> warnings,
                                       Capabilities caps) {
        StringBuilder sb = new StringBuilder();
        sb.append("kernel command line / boot arguments\n");
        sb.append("=====================================\n");
        sb.append("collected : ").append(Util.human()).append("\n");
        sb.append("read mode : ").append(Shell.hasRoot() ? "root" : "app").append("\n\n");

        boolean got = false;

        // ---- source 1: the real /proc/cmdline --------------------------------
        Shell.Result r1 = Shell.runReported("cat /proc/cmdline", 15000);
        String c1 = r1.out.trim();
        boolean denied1 = c1.length() == 0
                || c1.contains("Permission denied") || c1.contains("No such file");
        if (!denied1) {
            got = true;
            sb.append("--- /proc/cmdline (verbatim) ---------------------------------------\n");
            sb.append(c1).append("\n\n");
            sb.append("--- /proc/cmdline split into parameters ---------------------------\n");
            for (String tok : c1.split("\\s+")) {
                if (tok.trim().length() == 0) continue;
                int eq = tok.indexOf('=');
                if (eq > 0) {
                    sb.append(String.format("  %-38s = %s%n", tok.substring(0, eq), tok.substring(eq + 1)));
                } else {
                    sb.append("  ").append(tok).append("\n");
                }
            }
            sb.append("\n");
            index.append("cmdline.txt :: /proc/cmdline :: ok :: ").append(Util.bytes(c1.length())).append("\n");
        } else {
            sb.append("--- /proc/cmdline --------------------------------------------------\n");
            sb.append("NOT READABLE by this app.\n");
            sb.append("SELinux labels /proc/cmdline as `proc_cmdline` and denies it to both the\n");
            sb.append("`shell` and `untrusted_app` domains on production builds; only root can\n");
            sb.append("read it. The `ro.boot.*` properties below cover most of the same ground.\n\n");
            sb.append("IMPORTANT - what is NOT recoverable from ro.boot.*:\n");
            sb.append("  Kernel-only arguments such as `msm_drm.dsi_display0=<panel>` exist ONLY\n");
            sb.append("  in the command line. They are NOT mirrored into properties, so the panel\n");
            sb.append("  driver name cannot be recovered on a non-rooted device. Verified on the\n");
            sb.append("  moto g(100): the value qcom,mdss_dsi_vtdr6110c_ft3519_643_visionox_video\n");
            sb.append("  appears in /proc/cmdline but in no property, sysfs node or log the app can\n");
            sb.append("  read. Use one of these instead:\n");
            sb.append("    * /sys/class/drm/card*-DSI-*/modes - the active panel mode, no root needed\n");
            sb.append("    * dumpsys display - the loaded DisplayDeviceConfig brightness/nit table\n");
            sb.append("    * tools/augment_from_pc.ps1 - reads /proc/cmdline as root\n\n");
            index.append("cmdline.txt :: /proc/cmdline NOT READABLE")
                 .append(Shell.hasRoot() ? "" : " (needs root)")
                 .append(" :: panel args are NOT in ro.boot.* either\n");
        }

        // ---- source 2: /proc/bootconfig (Android 12+ GKI) --------------------
        Shell.Result r2 = Shell.runReported("cat /proc/bootconfig", 15000);
        String c2 = r2.out.trim();
        if (c2.length() > 0 && !c2.contains("Permission denied") && !c2.contains("No such file")) {
            got = true;
            sb.append("--- /proc/bootconfig ----------------------------------------------\n");
            sb.append(c2).append("\n\n");
            index.append("cmdline.txt :: /proc/bootconfig :: ok :: ").append(Util.bytes(c2.length())).append("\n");
        } else {
            sb.append("--- /proc/bootconfig ----------------------------------------------\n");
            sb.append("not present or not readable on this device")
              .append(c2.length() > 0 ? (" (" + firstLine(c2) + ")") : "")
              .append("\n\n");
            index.append("cmdline.txt :: /proc/bootconfig :: unavailable\n");
        }

        // ---- source 3: ro.boot.* properties (works everywhere) ---------------
        Shell.Result r3 = Shell.runReported("getprop", 30000);
        StringBuilder boots = new StringBuilder();
        int bootCount = 0;
        if (r3.out.length() > 0) {
            for (String line : r3.out.split("\n")) {
                // getprop format: [name]: [value]
                if (!line.startsWith("[ro.boot")) continue;
                boots.append("  ").append(line.trim()).append("\n");
                bootCount++;
            }
        }
        sb.append("--- ro.boot.* properties (always readable; mirror androidboot.*) ---\n");
        if (bootCount > 0) {
            got = true;
            sb.append("count: ").append(bootCount).append("\n\n").append(boots).append("\n");
            index.append("cmdline.txt :: ro.boot.* :: ").append(bootCount).append(" properties\n");
        } else {
            sb.append("(none found; getprop returned ")
              .append(Util.bytes(r3.out.length())).append(")\n\n");
            index.append("cmdline.txt :: ro.boot.* :: none found\n");
        }

        // ---- source 4: the device-tree bootargs, when reachable --------------
        Shell.Result r4 = Shell.runReported(
                "cat /proc/device-tree/chosen/bootargs 2>&1; "
                + "cat /sys/firmware/devicetree/base/chosen/bootargs 2>&1", 15000);
        String c4 = r4.out.trim();
        if (c4.length() > 0 && !c4.contains("Permission denied") && !c4.contains("No such file")) {
            sb.append("--- device-tree chosen/bootargs -----------------------------------\n");
            sb.append(c4).append("\n\n");
        }

        // ---- the part people actually come here for -------------------------
        sb.append("--- boot essentials ------------------------------------------------\n");
        String[][] interesting = {
                { "ro.boot.hardware", "SoC / hardware platform" },
                { "ro.boot.hardware.sku", "hardware SKU" },
                { "ro.boot.hab.product", "product codename (Motorola HAB)" },
                { "ro.boot.revision", "hardware revision" },
                { "ro.boot.hwrev", "hardware revision (raw)" },
                { "ro.boot.serialno", "device serial" },
                { "ro.boot.bootloader", "bootloader version" },
                { "ro.boot.baseband", "baseband" },
                { "ro.boot.slot_suffix", "active A/B slot" },
                { "ro.boot.verifiedbootstate", "verified boot state" },
                { "ro.boot.flash.locked", "bootloader lock state" },
                { "ro.boot.vbmeta.device_state", "vbmeta device state" },
                { "ro.boot.veritymode", "dm-verity mode" },
                { "ro.boot.bootreason", "why the device booted" },
                { "ro.boot.mode", "boot mode" },
                { "ro.boot.write_protect", "write protect" },
                { "ro.boot.coredump", "coredump setting" },
                { "ro.boot.dualsim", "dual SIM" },
                { "ro.boot.dynamic_partitions", "dynamic partitions" },
                { "ro.boot.dtbo_idx", "DTBO overlay index" },
                { "ro.boot.dtb_idx", "DTB index" },
                { "ro.boot.fstab_suffix", "fstab variant" },
                { "ro.boot.force_normal_boot", "forced normal boot" },
        };
        int found = 0;
        for (String[] kv : interesting) {
            Shell.Result v = Shell.app("getprop " + kv[0], 8000);
            String val = v.out.trim();
            if (val.length() == 0) continue;
            found++;
            sb.append(String.format("  %-34s = %-28s # %s%n", kv[0], val, kv[1]));
        }
        sb.append("\n  (").append(found).append(" of ").append(interesting.length)
          .append(" known boot properties present on this build)\n\n");

        // ---- panel / display parameters from the command line ----------------
        sb.append("--- display / panel parameters from boot args -----------------------\n");
        sb.append("These decide which panel driver the kernel binds, so they are the first\n");
        sb.append("thing to check when a panel bring-up misbehaves.\n\n");
        String[][] panelKeys = {
                { "msm_drm.dsi_display0", "primary DSI panel" },
                { "msm_drm.dsi_display1", "secondary DSI panel" },
                { "msm_drm.dsi_display2", "tertiary DSI panel" },
                { "msm_drm.dsi_display", "DSI panel" },
                { "mdss_mdp.panel", "MDP panel" },
                { "video", "framebuffer / video mode" },
                { "androidboot.display", "display override" },
                { "lcd_name", "LCD name (some vendors)" },
                { "LCM_ID", "LCM id (some vendors)" },
        };
        int panelFound = 0;
        String allArgs = c1 + " " + c2 + " " + c4;
        for (String[] pk : panelKeys) {
            // Look in the raw command line first, then in the properties.
            String val = findArg(allArgs, pk[0]);
            if (val == null) {
                Shell.Result v = Shell.app("getprop " + pk[0], 8000);
                String pv = v.out.trim();
                if (pv.length() > 0) val = pv;
            }
            if (val == null || val.length() == 0) continue;
            panelFound++;
            sb.append(String.format("  %-24s = %s%n", pk[0], val));
            sb.append(String.format("  %-24s   # %s%n", "", pk[1]));
        }
        if (panelFound == 0) {
            sb.append("  (no panel parameters found in the readable boot args on this build)\n\n");
            // On a non-rooted device this is the expected outcome, so give the reader the
            // sources that DO work rather than leaving them at a dead end.
            sb.append("--- panel identity from sources that need no root -------------------\n");
            Shell.Result drm = Shell.app(
                    "for f in /sys/class/drm/card*-DSI-*/status /sys/class/drm/card*-DSI-*/modes "
                    + "/sys/class/drm/card*/status; do [ -e \"$f\" ] || continue; "
                    + "echo \"  $f = $(cat $f 2>/dev/null | head -c 200)\"; done", 20000);
            if (drm.out.trim().length() > 0) {
                sb.append("DRM connector state (readable, no root):\n").append(drm.out);
                sb.append("\n  This gives the panel's ACTIVE MODE (resolution/refresh/video-mode flag)\n");
                sb.append("  but not the kernel driver string - that lives only in /proc/cmdline.\n\n");
            } else {
                sb.append("DRM connector state: not readable by this app.\n");
                sb.append("  /sys/class/drm/card*-DSI-1/{status,modes} are labelled\n");
                sb.append("  u:object_r:vendor_sysfs_graphics:s0, which root and the adb shell may\n");
                sb.append("  read but the untrusted_app domain may not. Confirmed on the moto g(100):\n");
                sb.append("  the same label also covers /sys/class/backlight/*, which is why every\n");
                sb.append("  display sysfs source is empty for this app while root sees them all.\n");
                sb.append("  Read them as root with:\n");
                sb.append("    adb shell cat /sys/class/drm/card0-DSI-1/modes\n");
                sb.append("  or via tools/augment_from_pc.ps1.\n\n");
            }
            sb.append("The loaded brightness/nit tables (what the framework actually uses) are in\n");
            sb.append("display/brightness_from_services.txt - that is the source to compare against\n");
            sb.append("when a brightness curve misbehaves.\n");
            sb.append("For the raw kernel driver string and cmdline, run as root:\n");
            sb.append("  pwsh -File tools/augment_from_pc.ps1\n");
        }

        Storage.WriteResult w = Storage.writeText(ctx, s, "cmdline.txt", sb.toString());
        index.append("cmdline.txt :: ").append(w.ok ? ("ok (" + Util.bytes(sb.length()) + ")")
                : ("FAILED: " + w.detail)).append("\n");
        if (!got) {
            warnings.add("cmdline 三个来源（/proc/cmdline、/proc/bootconfig、ro.boot.*）都没取到数据。");
        }
    }

    /** Finds {@code key=value} inside a whitespace-separated argument string. */
    private static String findArg(String args, String key) {
        if (args == null || args.length() == 0) return null;
        int i = 0;
        String needle = key + "=";
        while ((i = args.indexOf(needle, i)) >= 0) {
            // Require a token boundary before the key so "xmsm_drm.dsi_display0" does not match.
            boolean atStart = i == 0 || Character.isWhitespace(args.charAt(i - 1));
            if (atStart) {
                int end = args.indexOf(' ', i);
                if (end < 0) end = args.length();
                String v = args.substring(i + needle.length(), end).trim();
                // Trailing tokens such as a lone ':' are role markers, not part of the name.
                return v;
            }
            i += needle.length();
        }
        return null;
    }

    // ---------------------------------------------------------------- dmesg

    private static void collectDmesg(Context ctx, Storage.Session s, Options o,
                                     StringBuilder index, List<String> warnings) {
        // Plain `dmesg` keeps the kernel's monotonic timestamps, which is what you want when
        // correlating against logcat. `-t` drops them; we grab both when it is cheap.
        int tailBytes = Math.max(64, o.dmesgTailKb) * 1024;

        // ------------------------------------------------------------------
        // Do NOT call dmesg if we already know it is blocked.
        //
        // A blocked dmesg does not merely fail: the seccomp kill makes the kernel write a
        // tombstone into /data/tombstones/, a ~32-entry ring buffer holding real native-crash
        // evidence. Measured on the test device, five collection runs were enough to overwrite
        // every genuine tombstone. So the verdict is cached and persisted, and this returns
        // immediately without spawning anything.
        // ------------------------------------------------------------------
        Prefs prefs = Prefs.get(ctx);
        DmesgGate.loadCached(prefs);

        if (DmesgGate.isBlocked()) {
            // dmesg itself is unusable, but the kernel log is NOT lost: `logcat -b kernel`
            // exposes the same kernel ring buffer through logd, which an app may read whenever
            // READ_LOGS is held. This is the normal, working path on Android 11+.
            writeDmesgUnavailable(ctx, s, index, warnings, "seccomp SIGSYS (cached verdict)",
                    DmesgGate.reason());
            collectKernelLogcat(ctx, s, o, index, warnings);
            return;
        }

        Shell.Result r = Shell.runReported("dmesg", 30000);
        boolean gotRaw = r.out.length() > 0;

        if (!gotRaw) {
            // Confirm whether this is the permanent seccomp block before trying more variants -
            // each additional attempt would cost another tombstone.
            Capabilities.DmesgProbe probe = Capabilities.probeDmesg();
            if (probe.seccompBlocked) {
                DmesgGate.markBlocked(prefs, probe.detail);
                writeDmesgUnavailable(ctx, s, index, warnings,
                        "seccomp SIGSYS (exit=" + probe.exitCode + ")", probe.detail);
                collectKernelLogcat(ctx, s, o, index, warnings);
                return;
            }

            // Not seccomp: the failure is environmental, so the alternate forms are worth trying.
            Shell.Result r2 = Shell.runReported("dmesg -t", 20000);
            if (r2.out.length() > 0) { r = r2; gotRaw = true; }
        }
        if (!gotRaw && !DmesgGate.commandUsesDmesg("cat /dev/kmsg")) {
            Shell.Result r3 = Shell.runReported("cat /dev/kmsg", 15000);
            if (r3.out.length() > 0) { r = r3; gotRaw = true; }
        }

        if (!gotRaw) {
            // Distinguish "the kernel refuses this process" from "the tool is missing", because
            // only the latter is worth retrying with a different command.
            Capabilities.DmesgProbe probe = Capabilities.probeDmesg();
            String why;
            if (probe.seccompBlocked) {
                why = seccompExplanation(probe.exitCode);
            } else {
                why = "The kernel ring buffer could not be read from this app.\n\n"
                    + "command : " + r.cmd + "\n"
                    + "mode    : " + r.modeName() + "\n"
                    + "exit    : " + r.code + "\n"
                    + "stderr  : " + Util.trimTo(r.err, 2000) + "\n\n"
                    + "How to get it anyway:\n"
                    + "  1. adb:       adb root; adb shell dmesg > dmesg.txt\n"
                    + "  2. pc helper: pwsh -File tools/augment_from_pc.ps1\n"
                    + "  3. kernel_log.txt in this directory uses `logcat -b kernel`, which needs\n"
                    + "     no root at all - check whether it has content.\n"
                    + "  4. This app still writes everything it CAN read - see kernel/ for /proc data.\n";
            }
            String note = "dmesg NOT AVAILABLE\n"
                    + "===================\n\n"
                    + why + "\n"
                    + "detail: " + probe.detail + "\n";
            Storage.WriteResult w = Storage.writeText(ctx, s, "dmesg.txt", note);
            index.append("dmesg.txt :: NOT AVAILABLE (")
                 .append(probe.seccompBlocked ? "seccomp SIGSYS" : ("exit=" + r.code))
                 .append(", ").append(r.modeName()).append(")")
                 .append(w.ok ? "" : " [placeholder write also failed]").append("\n");
            warnings.add("dmesg 取不到" + (probe.seccompBlocked
                    ? "：被内核 seccomp 过滤器拦截（SIGSYS），任何授权都绕不过，需用 adb 导出。"
                    : "（" + r.modeName() + " exit=" + r.code + "），需 root 或用 adb 导出。"));
            // Whatever the reason dmesg failed, the logd kernel buffer is an independent channel
            // and may well work. Never stop at the placeholder.
            collectKernelLogcat(ctx, s, o, index, warnings);
            return;
        }

        String text = r.out;
        boolean truncated = false;
        if (tailBytes > 0 && text.length() > tailBytes) {
            text = "[... truncated to the last " + Util.bytes(tailBytes) + " of "
                 + Util.bytes(r.out.length()) + " ...]\n\n"
                 + text.substring(text.length() - tailBytes);
            truncated = true;
        }

        Storage.WriteResult w = Storage.writeText(ctx, s, "dmesg.txt", text);
        index.append("dmesg.txt :: ").append(w.ok ? "ok" : ("FAILED: " + w.detail))
             .append(" :: ").append(r.modeName()).append(" exit=").append(r.code)
             .append(" :: ").append(Util.bytes(text.length()))
             .append(truncated ? " (tail)" : " (full)").append("\n");

        // dmesg worked, so this device has root or an unconfined domain - the kernel ring buffer
        // is already captured above. Still write kernel_log.txt, because the header of every run
        // and verify.txt reference it, and a missing file would read as a gap rather than as
        // "not needed here".
        Storage.writeText(ctx, s, "kernel_log.txt",
                "kernel_log.txt not used on this device\n"
              + "======================================\n\n"
              + "A real `dmesg` capture succeeded here (see dmesg.txt), so the logcat kernel\n"
              + "buffer was not needed as a substitute.\n\n"
              + "Why this file exists at all: on an ordinary app the kernel denies the syslog\n"
              + "syscall outright (seccomp), and `logcat -b kernel` is then the only working\n"
              + "kernel-log channel. This device is not in that situation.\n\n"
              + "dmesg read mode : " + r.modeName() + "\n"
              + "dmesg bytes     : " + Util.bytes(text.length()) + "\n"
              + (truncated ? "dmesg was truncated to the configured tail.\n" : "")
              + "\nFor completeness, the kernel buffer as seen by logd is in logcat_kernel.txt,\n"
              + "and the kernel log levels are summarised in dmesg_levels.txt.\n");
        index.append("kernel_log.txt :: not needed (real dmesg succeeded; see dmesg.txt)\n");

        // A second copy with human-readable timestamps is genuinely useful on newer kernels.
        Shell.Result t = Shell.runReported("dmesg -T", 25000);
        if (t.out.length() > 0) {
            String tt = t.out;
            if (tailBytes > 0 && tt.length() > tailBytes) {
                tt = tt.substring(tt.length() - tailBytes);
            }
            Storage.writeText(ctx, s, "dmesg_human_time.txt",
                    "dmesg -T (wall-clock timestamps; note the kernel's own clock may be skewed)\n\n" + tt);
            index.append("dmesg_human_time.txt :: ok :: ").append(t.modeName()).append("\n");
        } else {
            index.append("dmesg_human_time.txt :: unavailable (dmesg -T unsupported)\n");
        }

        // Kernel log level distribution is a cheap way to spot a noisy driver.
        Shell.Result lv = Shell.runReported(
                "dmesg | sed -n 's/^<\\?\\([0-9]\\)\\?>\\?.*/\\1/p' | sort | uniq -c | sort -rn", 25000);
        if (lv.out.trim().length() > 0) {
            Storage.writeText(ctx, s, "dmesg_levels.txt",
                    "dmesg line counts by kernel log level (0=emerg .. 7=debug)\n\n" + lv.out);
        }
    }

    // --------------------------------------------------------------- logcat

    private static void collectLogcat(Context ctx, Storage.Session s, Options o,
                                      StringBuilder index, List<String> warnings, Progress cb) {
        // Roughly 128 bytes per threadtime line - good enough to translate the KB budget.
        int lines = Math.max(200, (o.logTailKb * 1024) / 128);

        // We are on a background thread here, so it is safe (and worth it) to run the buffer probe
        // synchronously and get the accurate buffer set for this device.
        step(cb, "logcat", "探测日志缓冲区…");
        Logcat.probeBlocking();

        // buffer sizes first: tells you whether the log even had a chance to hold the event
        Shell.Result g = Shell.runReported("logcat -g", 15000);
        if (g.out.length() > 0) {
            Storage.writeText(ctx, s, "logcat_buffer_sizes.txt",
                    "logcat buffer sizes as seen by this app (mode=" + g.modeName() + ")\n"
                    + "buffer selection: " + Logcat.detail() + "\n\n" + g.text());
            index.append("logcat_buffer_sizes.txt :: ok :: ").append(g.modeName()).append("\n");
        }

        boolean anyData = false;

        // Per-buffer files are opt-out: some users only ever read the merged stream and do not
        // want six near-duplicate files in every run.
        if (o.logBuffers) {
            List<String> reportable = Logcat.reportableBuffers();
            index.append("logcat buffers :: ").append(Logcat.detail()).append("\n");

            for (String b : reportable) {
                String out = captureLogcatBuffer(b, lines);
                if (out == null) {
                    index.append("logcat_").append(b).append(".txt :: unsupported on this device\n");
                    continue;
                }
                if (out.length() == 0) {
                    index.append("logcat_").append(b).append(".txt :: empty\n");
                    continue;
                }
                String header = "# logcat buffer '" + b + "'\n"
                        + "# format: threadtime   lines: last " + lines + "\n"
                        + "# collected: " + Util.human() + "\n"
                        + "# read mode: " + (Shell.hasRoot() ? "root" : "app")
                        + (Shell.hasRoot() ? "" : "  <-- if this looks empty, READ_LOGS is not granted")
                        + "\n\n";
                Storage.WriteResult w = Storage.writeText(ctx, s, "logcat_" + b + ".txt", header + out);
                anyData |= w.ok;
                index.append("logcat_").append(b).append(".txt :: ")
                     .append(w.ok ? "ok" : ("FAILED: " + w.detail))
                     .append(" :: ").append(Util.bytes(out.length())).append("\n");
            }
        } else {
            index.append("logcat per-buffer files :: SKIPPED (disabled in settings)\n");
        }

        // Merged view across every buffer, which is what most people actually read.
        step(cb, "logcat", "合并全部缓冲区…");
        String merged = captureMergedLogcat(lines);
        if (merged != null && merged.length() > 0) {
            Storage.WriteResult w = Storage.writeText(ctx, s, "logcat_all.txt",
                    "# every logcat buffer merged, chronological\n"
                    + "# buffers: " + Logcat.detail() + "\n"
                    + "# collected: " + Util.human() + "\n\n" + merged);
            index.append("logcat_all.txt :: ").append(w.ok ? "ok" : ("FAILED: " + w.detail))
                 .append(" :: ").append(Util.bytes(merged.length())).append("\n");
            anyData = true;
        } else {
            index.append("logcat_all.txt :: unavailable\n");
        }

        // Focused extracts that make a debug run immediately readable.
        String[][] greps = {
                { "logcat_fatal.txt", "FATAL EXCEPTION|beginning of crash|Fatal signal|>>> .* <<<",
                  "crashes and fatal signals" },
                { "logcat_anr.txt", "ANR in |Application Not Responding|Reason:",
                  "ANR reports" },
                { "logcat_native_crash.txt", "libc|DEBUG|backtrace|tombstone",
                  "native (libc) crash and tombstone noise" },
                { "logcat_watchdog.txt", "Watchdog|WATCHDOG|system_server crash",
                  "watchdog kills" },
                { "logcat_lowmemory.txt", "lowmemorykiller|LowMemoryKiller|lmkd|Out of memory|oom",
                  "low-memory kills" },
                { "logcat_display.txt", "SurfaceFlinger|DisplayDeviceConfig|brightness|backlight|hbm|HBM|dsi|panel|display_id",
                  "display / panel / backlight lines" },
        };
        for (String[] spec : greps) {
            String f = spec[0];
            String pattern = spec[1];
            String what = spec[2];
            // Reuse the merged capture from above instead of re-running logcat for every pattern.
            String out = merged != null ? merged : "";
            StringBuilder hits = new StringBuilder();
            int n = 0;
            if (out.length() > 0) {
                String[] arr = out.split("\n");
                for (String line : arr) {
                    if (matchesAny(line, pattern)) {
                        hits.append(line).append("\n");
                        n++;
                    }
                }
            }
            String body = "# extract: " + what + "\n"
                    + "# filter : " + pattern + "\n"
                    + "# matched: " + n + " line(s)\n\n" + hits;
            Storage.WriteResult w = Storage.writeText(ctx, s, f, body);
            index.append(f).append(" :: ").append(w.ok ? (n + " lines") : ("FAILED: " + w.detail)).append("\n");
        }

        if (!anyData) {
            warnings.add("logcat 没有取到任何数据。若设备非 root，需要：adb shell pm grant "
                    + ctx.getPackageName() + " android.permission.READ_LOGS");
        }
    }

    /** Returns null when the buffer does not exist on this device. */
    private static String captureLogcatBuffer(String buffer, int lines) {
        String bufArgs = "-b " + buffer;
        String out = runLogcat(bufArgs, lines);
        if (out != null) return out;

        String err = lastLogcatError.toLowerCase();
        if (err.contains("unknown buffer") || err.contains("not a valid")
                || err.contains("no such") || err.contains("invalid buffer")) {
            return null;
        }
        return "";
    }

    /** The merged stream, using whatever buffer syntax this device accepted at probe time. */
    private static String captureMergedLogcat(int lines) {
        String out = runLogcat(null, lines);
        if (out != null) return out;
        // Last resort: the classic single-buffer command, which works everywhere.
        out = runLogcat("-b main", lines);
        return out == null ? "" : out;
    }

    private static volatile String lastLogcatError = "";

    /**
     * Runs one logcat dump. {@code bufArgs} is either null (use the probed buffer selection) or an
     * explicit {@code "-b <name>"}. Returns null only when the command produced nothing at all.
     *
     * <p>Three forms are tried, most capable first, because none of the flags is universal:
     * <ol>
     *   <li>{@code -t <n>} - bounds the output at the source. Added in Android 6.0.</li>
     *   <li>{@code | tail -n <n>} - bounds it at the consumer. Works on older logcat, but a
     *       pipeline hides logcat's own exit status, so an empty result here is ambiguous.</li>
     *   <li>no bound at all - the only form guaranteed to exist. The full buffer is read and the
     *       caller's byte budget still applies, so this cannot run away.</li>
     * </ol>
     */
    private static String runLogcat(String bufArgs, int lines) {
        String selection;
        if (bufArgs != null) {
            selection = bufArgs;
        } else {
            StringBuilder sb = new StringBuilder();
            for (String a : Logcat.bufferArgs()) sb.append(a).append(" ");
            selection = sb.toString().trim();
        }

        Shell.Result r = Shell.runReported(
                "logcat " + selection + " -d -v threadtime -t " + lines, 90000);
        if (r.out.length() > 0) return r.out;
        lastLogcatError = r.err + " " + r.out;

        Shell.Result r2 = Shell.runReported(
                "logcat " + selection + " -d -v threadtime | tail -n " + lines, 90000);
        if (r2.out.length() > 0) return r2.out;
        lastLogcatError = r2.err + " " + r2.out;

        // Unbounded form: no -t, no pipe. Slowest but the most portable.
        Shell.Result r3 = Shell.runReported("logcat " + selection + " -d -v threadtime", 180000);
        if (r3.out.length() > 0) {
            // Honour the caller's line budget here instead of in logcat.
            String[] all = r3.out.split("\n");
            if (all.length > lines) {
                StringBuilder sb = new StringBuilder();
                for (int i = all.length - lines; i < all.length; i++) sb.append(all[i]).append("\n");
                return sb.toString();
            }
            return r3.out;
        }
        lastLogcatError = r3.err + " " + r3.out;
        return null;
    }

    /** Why a dumpsys call produced nothing, and what unlocks it. Shared by the failure paths. */
    private static String dumpDeniedAdvice(Context ctx) {
        String pkg = ctx.getPackageName();
        return "#   1. the app is missing the DUMP permission:\n"
             + "#        adb shell pm grant " + pkg + " android.permission.DUMP\n"
             + "#   2. individual services also require their own permission, most often\n"
             + "#      PACKAGE_USAGE_STATS (activity, package, batterystats, alarm, ...):\n"
             + "#        adb shell appops set --uid " + pkg + " GET_USAGE_STATS allow\n"
             + "#   3. some builds write the denial to stderr only, which is why the exit code\n"
             + "#      above matters more than the empty stdout.\n"
             + "#   4. everything works if the device has root, or run the sweep from a PC:\n"
             + "#        pwsh -File tools/augment_from_pc.ps1";
    }

    private static boolean matchesAny(String line, String pipeSeparated) {
        for (String p : pipeSeparated.split("\\|")) {
            if (p.length() > 0 && line.contains(p)) return true;
        }
        return false;
    }

    /**
     * True when {@code name} appears in the device's {@code dumpsys -l} output.
     *
     * <p>Compared case-insensitively and also matched on the last dot-separated segment, because
     * some listings print fully qualified names ({@code android.security.identity}) while callers
     * use the short form.
     */
    static boolean containsService(List<String> services, String name) {
        if (services == null || name == null) return false;
        for (String s : services) {
            if (s == null) continue;
            if (s.equalsIgnoreCase(name)) return true;
            int dot = s.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < s.length() && s.substring(dot + 1).equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** Lists the names from {@code candidates} that this build does not have, for the index. */
    private static String describeMissing(String[] candidates, List<String> services) {
        List<String> miss = new ArrayList<String>();
        for (String c : candidates) {
            if (!containsService(services, c)) miss.add(c);
        }
        return Util.join(" ", miss);
    }

    /**
     * Pulls the permission name out of a dumpsys permission-denial message.
     *
     * <p>A real message from a moto g(100) on Android 12:
     * <pre>
     * Permission Denial: can't dump ActivityManager from from pid=12386, uid=10343
     *   due to missing android.permission.PACKAGE_USAGE_STATS permission
     * </pre>
     * Reporting a blanket "grant DUMP" here would be actively misleading, because DUMP is
     * already granted and the service wants something else.
     *
     * @return the fully-qualified permission, or null when the message does not name one
     */
    static String extractNeededPermission(String body) {
        if (body == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:missing|requires?|needs?)\\s+(android\\.permission\\.[A-Z_0-9]+)")
                .matcher(body);
        if (m.find()) return m.group(1);

        // Any android.permission.* mentioned in a denial message is a reasonable guess.
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(
                "(android\\.permission\\.[A-Z_0-9]+)").matcher(body);
        if (m2.find()) {
            String p = m2.group(1);
            // Do not echo DUMP back at the user when DUMP is not the thing that is missing.
            if (!"android.permission.DUMP".equals(p)) return p;
            return "android.permission.DUMP";
        }
        return null;
    }

    // --------------------------------------------------------------- dumpsys

    private static void collectDumpsys(Context ctx, Storage.Session s, Options o,
                                       StringBuilder index, List<String> warnings,
                                       Progress cb, long deadline, Report rep) {
        // The service list itself is diagnostic gold: it changes between builds.
        Shell.Result l = Shell.runReported("dumpsys -l", 30000);
        List<String> services = new ArrayList<String>();
        if (l.out.length() > 0) {
            Storage.writeText(ctx, s, "dumpsys/_service_list.txt",
                    "# `dumpsys -l` on this device (mode=" + l.modeName() + ")\n\n" + l.text());
            String[] arr = l.out.split("\n");
            for (String line : arr) {
                String t = line.trim();
                if (t.length() == 0) continue;
                if (t.startsWith("Currently running services")) continue;
                if (t.startsWith("Service ") || t.startsWith("----")) continue;
                // Some builds indent the names under a header.
                if (t.contains(" ")) {
                    String[] parts = t.split("\\s+");
                    t = parts[parts.length - 1];
                }
                if (t.length() > 0 && !services.contains(t)) services.add(t);
            }
            index.append("dumpsys/_service_list.txt :: ok :: ")
                 .append(services.size()).append(" services (mode=").append(l.modeName()).append(")\n");
        } else {
            index.append("dumpsys/_service_list.txt :: FAILED (")
                 .append(l.modeName()).append(" exit=").append(l.code).append(")\n");
            warnings.add("无法列出 dumpsys 服务列表（" + l.modeName() + " exit=" + l.code
                    + "）。需要 root 或 DUMP 权限。");
        }

        // ------------------------------------------------------- always-on set
        //
        // The hardcoded lists are a starting point tuned on one device, so they are filtered
        // against the service list this device actually reports. Service names are far from
        // universal: "cpuinfo" and "thermalservice" are missing on many builds, vendor ROMs add
        // and rename their own, and Android 13+ dropped some. Attempting a name that does not
        // exist costs a process spawn and produces a "Can't find service" stub instead of data -
        // noise that looks like a failure on a device where nothing is actually wrong.
        //
        // When the service list could not be read, the lists are used unfiltered: a wasted
        // attempt is better than silently dropping a service that would have worked.
        final boolean haveList = !services.isEmpty();
        List<String> core = new ArrayList<String>();
        for (String c : Prefs.CORE_DUMPSYS) {
            if (core.contains(c)) continue;
            if (haveList && !containsService(services, c)) continue;
            core.add(c);
        }
        int coreSkipped = Prefs.CORE_DUMPSYS.length - core.size();

        // -------------------------------------------------------- display set
        List<String> display = new ArrayList<String>();
        if (o.dumpsysExtra) {
            for (String c : Prefs.DISPLAY_DUMPSYS) {
                if (display.contains(c)) continue;
                // "SurfaceFlinger" is case-sensitive and is NOT in `dumpsys -l` output on most
                // builds (the list is lower-case), so it must not be filtered away.
                if (haveList && !"SurfaceFlinger".equals(c) && !containsService(services, c)) continue;
                display.add(c);
            }
        }

        if (haveList && coreSkipped > 0) {
            index.append("dumpsys/ :: ").append(coreSkipped)
                 .append(" core service name(s) not present on this build, skipped: ")
                 .append(describeMissing(Prefs.CORE_DUMPSYS, services)).append("\n");
        }

        // ------------------------------------------------------------- full
        List<String> full = new ArrayList<String>();
        if (o.dumpsysFull && !services.isEmpty()) {
            for (String svc : services) if (!full.contains(svc)) full.add(svc);
        }

        // Merge, keeping a stable order: core first (most likely to succeed), then the rest.
        List<String> todo = new ArrayList<String>();
        for (String c : core)    if (!todo.contains(c)) todo.add(c);
        for (String c : display) if (!todo.contains(c)) todo.add(c);
        for (String c : full)    if (!todo.contains(c)) todo.add(c);

        if (todo.isEmpty()) {
            index.append("dumpsys/* :: nothing to dump\n");
            return;
        }

        int done = 0, failed = 0, empty = 0, missing = 0;
        long dumpedBytes = 0;
        List<String> failures = new ArrayList<String>();

        for (String svc : todo) {
            if (System.currentTimeMillis() > deadline) {
                index.append("dumpsys/").append(Util.slug(svc)).append(".txt :: SKIPPED (budget exhausted)\n");
                warnings.add("时间预算用尽，dumpsys 未跑完（停在 " + svc + "）。");
                break;
            }
            done++;
            if (done % 5 == 0 || done == 1) {
                step(cb, "dumpsys", "(" + done + "/" + todo.size() + ") " + svc);
            }

            // `dumpsys <svc>` prints a big header; keep everything, it documents itself.
            Shell.Result r = Shell.runReported("dumpsys " + svc, 40000);
            String body = r.text();

            if (body.length() == 0) {
                empty++;
                index.append("dumpsys/").append(Util.slug(svc)).append(".txt :: EMPTY (")
                     .append(r.modeName()).append(" exit=").append(r.code).append(")")
                     .append(r.err.length() > 0 ? " :: " + firstLine(r.err) : "").append("\n");
                if (r.code != 0) {
                    failed++;
                    if (failures.size() < 12) failures.add(svc + " (exit=" + r.code + ")");
                }
                // ALWAYS leave a file behind. Returning without one meant a full sweep on a
                // device where dumpsys is denied produced an almost-empty dumpsys/ directory
                // with no record that ~150 services were even attempted - the same "the log
                // just isn't there" symptom as the dmesg complaint.
                Storage.writeText(ctx, s, "dumpsys/" + Util.slug(svc) + ".txt",
                        "# dumpsys " + svc + "\n"
                      + "# NOT AVAILABLE - the command produced no output.\n"
                      + "# read mode : " + r.modeName() + "\n"
                      + "# exit code : " + r.code + (r.timedOut ? "  (TIMED OUT)" : "") + "\n"
                      + (r.err.length() > 0 ? ("# stderr    : " + Util.trimTo(r.err, 2000) + "\n") : "")
                      + "\n# Likely causes and fixes:\n"
                      + dumpDeniedAdvice(ctx) + "\n"
                      + "# This service is listed in dumpsys -l on this device, so the name is\n"
                      + "# correct - the command itself could not run.\n");
                continue;
            }

            boolean looksDenied = body.contains("Permission Denial")
                    || body.contains("android.permission.DUMP")
                    || body.contains("Permission denied")
                    || body.contains("SecurityException");

            // The service does not exist under this name on this build. That is an expected
            // outcome for a full sweep (the service list is version-specific), not a failure.
            boolean noSuchService = body.contains("Can't find service")
                    || body.contains("Unknown service")
                    || body.contains("No service published")
                    || body.contains("does not exist");

            if (noSuchService && body.length() < 2000) {
                missing++;
                index.append("dumpsys/").append(Util.slug(svc)).append(".txt :: NO SUCH SERVICE on this build\n");
                Storage.writeText(ctx, s, "dumpsys/" + Util.slug(svc) + ".txt",
                        "# dumpsys " + svc + "\n"
                      + "# NO SUCH SERVICE on this build.\n"
                      + "#\n"
                      + "# The service name is not registered here, so there is nothing to dump.\n"
                      + "# This is normal: Android adds and removes services between releases, and\n"
                      + "# vendors add their own. It is NOT a permission problem and nothing is\n"
                      + "# misconfigured. See _service_list.txt for what this device really has.\n"
                      + "\n# raw output:\n" + Util.trimTo(body, 2000) + "\n");
                continue;
            }

            if (looksDenied && body.length() < 4000) {
                failed++;
                // Pull the ACTUAL missing permission out of the message. Reporting a blanket
                // "grant DUMP" is wrong when DUMP is already granted and the service is asking
                // for something else entirely (PACKAGE_USAGE_STATS, BATTERY_STATS, ...).
                String needed = extractNeededPermission(body);
                String advice = (needed != null)
                        ? ("# This service needs: " + needed + "\n"
                           + "#   adb shell pm grant " + ctx.getPackageName() + " " + needed + "\n"
                           + "#   (or: adb shell appops set --uid " + ctx.getPackageName()
                           + " GET_USAGE_STATS allow  -- for *USAGE_STATS permissions)\n")
                        : ("# The app is not allowed to dump this service.\n"
                           + "# If DUMP is already granted, this service wants a different, service-specific\n"
                           + "# permission - the message below names it.\n");
                if (failures.size() < 12) failures.add(svc + (needed != null ? (" (needs " + needed + ")")
                                                                             : " (permission denied)"));
                index.append("dumpsys/").append(Util.slug(svc)).append(".txt :: DENIED")
                     .append(needed != null ? (" (needs " + needed + ")") : "").append("\n");
                Storage.writeText(ctx, s, "dumpsys/" + Util.slug(svc) + ".txt",
                        "# dumpsys " + svc + "\n" + advice + "\n" + body);
                continue;
            }

            String header = "# dumpsys " + svc + "\n"
                    + "# collected: " + Util.human() + "\n"
                    + "# read mode: " + r.modeName() + "\n\n";
            Storage.WriteResult w = Storage.writeText(ctx, s, "dumpsys/" + Util.slug(svc) + ".txt",
                    header + body);
            if (w.ok) {
                dumpedBytes += body.length();
                if (body.length() < 64) {
                    empty++;
                    index.append("dumpsys/").append(Util.slug(svc)).append(".txt :: ok but tiny (")
                         .append(body.length()).append(" B)\n");
                } else {
                    index.append("dumpsys/").append(Util.slug(svc)).append(".txt :: ok :: ")
                         .append(Util.bytes(body.length())).append("\n");
                }
            } else {
                failed++;
                if (failures.size() < 12) failures.add(svc + " (write failed)");
                index.append("dumpsys/").append(Util.slug(svc)).append(".txt :: WRITE FAILED: ")
                     .append(w.detail).append("\n");
            }
        }

        index.append("dumpsys/ :: total ").append(todo.size()).append(" attempted, ")
             .append(done).append(" run, ").append(failed).append(" denied, ")
             .append(missing).append(" not-on-this-build, ")
             .append(empty).append(" empty, ").append(Util.bytes(dumpedBytes)).append(" captured\n");
        // Record the aggregate so the self-check can distinguish "all fine" from
        // "most services were refused", which a per-file audit alone cannot see.
        if (failed > 0) {
            rep.gaps.add(new String[] {
                    "dumpsys/ (summary)",
                    failed + " of " + todo.size() + " services were denied - see index.txt" });
        }
        if (!failures.isEmpty()) {
            index.append("dumpsys/ :: sample denials: ").append(Util.join(", ", failures)).append("\n");
        }
        if (failed > 0) {
            warnings.add("dumpsys 有 " + failed + " 个服务因权限被拒。"
                    + (Shell.hasRoot() ? "已用 root，说明是服务自身要求的额外权限。"
                                       : "部分服务需要 DUMP 之外的专用权限，详见 dumpsys/ 下对应文件顶部。"));
        }

        // Full merged dump, when we have the privileges and the room for it.
        if (o.dumpsysFull && System.currentTimeMillis() < deadline) {
            step(cb, "dumpsys", "合并全量 dump…");
            Shell.Result r = Shell.runReported("dumpsys", 180000);
            if (r.out.length() > 0) {
                Storage.WriteResult w = Storage.writeText(ctx, s, "dumpsys_all.txt",
                        "# `dumpsys` with no arguments - every service in one stream\n"
                        + "# mode: " + r.modeName() + "  collected: " + Util.human() + "\n\n" + r.out);
                index.append("dumpsys_all.txt :: ").append(w.ok ? "ok" : ("FAILED: " + w.detail))
                     .append(" :: ").append(Util.bytes(r.out.length())).append("\n");
            } else {
                index.append("dumpsys_all.txt :: unavailable (").append(r.modeName())
                     .append(" exit=").append(r.code).append(")\n");
            }
        }
    }

    // ------------------------------------------------------------------ prop

    private static void collectProp(Context ctx, Storage.Session s, StringBuilder index) {
        Shell.Result r = Shell.runReported("getprop", 30000);
        if (r.out.length() > 0) {
            Storage.WriteResult w = Storage.writeText(ctx, s, "prop.txt",
                    "# getprop\n# mode: " + r.modeName() + "  collected: " + Util.human() + "\n\n" + r.out);
            index.append("prop.txt :: ").append(w.ok ? "ok" : ("FAILED: " + w.detail))
                 .append(" :: ").append(Util.bytes(r.out.length())).append("\n");
        } else {
            index.append("prop.txt :: FAILED (").append(r.modeName()).append(" exit=")
                 .append(r.code).append(")\n");
            // Reading the property area directly still works on some builds.
            Shell.Result r2 = Shell.runReported(
                    "for f in /system/build.prop /vendor/build.prop /odm/etc/build.prop; do "
                    + "echo \"### $f\"; cat $f 2>/dev/null; done", 20000);
            if (r2.out.length() > 0) {
                Storage.writeText(ctx, s, "prop.txt", "# build.prop files (getprop was denied)\n\n" + r2.out);
                index.append("prop.txt :: recovered from build.prop files\n");
            }
        }

        // Sorted, de-duplicated view minus the noisy dalvik/ro.boot entries - easier to diff.
        Shell.Result r3 = Shell.runReported(
                "getprop | sed -n 's/^\\[\\(.*\\)\\]: \\[\\(.*\\)\\]/\\1=\\2/p' | sort", 30000);
        if (r3.out.length() > 0) {
            Storage.writeText(ctx, s, "prop_sorted.txt",
                    "# getprop, 'key=value' per line, sorted\n\n" + r3.out);
            index.append("prop_sorted.txt :: ok :: ").append(Util.bytes(r3.out.length())).append("\n");
        } else {
            // No silent gap: this transform needs a `sed`/`sort` that some minimal ROMs lack.
            Storage.writeText(ctx, s, "prop_sorted.txt",
                    "# getprop, 'key=value' per line, sorted\n"
                  + "# NOT PRODUCED - the transform pipeline returned nothing.\n"
                  + "#\n"
                  + "# The unsorted dump is still in prop.txt; only the sed/sort reformat failed,\n"
                  + "# which happens on ROMs with a cut-down sed or sort.\n"
                  + "# read mode: " + r3.modeName() + "  exit: " + r3.code + "\n");
            index.append("prop_sorted.txt :: NOT PRODUCED (sed/sort pipeline empty)\n");
        }

        // The handful of properties that explain the build you are debugging.
        String[] interesting = {
                "ro.build.type", "ro.build.tags", "ro.build.version.release", "ro.build.version.sdk",
                "ro.build.fingerprint", "ro.product.model", "ro.product.device", "ro.board.platform",
                "ro.hardware", "ro.boot.verifiedbootstate", "ro.boot.flash.locked",
                "ro.debuggable", "ro.secure", "ro.adb.secure", "persist.sys.usb.config",
                "ro.boot.selinux", "ro.build.description", "ro.vendor.build.fingerprint",
                "ro.sf.lcd_density", "persist.sys.display", "ro.kernel.qemu",
        };
        StringBuilder sb = new StringBuilder();
        sb.append("selected properties\n===================\n\n");
        for (String k : interesting) {
            Shell.Result v = Shell.app("getprop " + k, 8000);
            sb.append(String.format("%-34s = %s%n", k, v.out.trim()));
        }
        Storage.writeText(ctx, s, "prop_selected.txt", sb.toString());
        index.append("prop_selected.txt :: ok\n");
    }

    // ---------------------------------------------------------------- kernel

    /** Files under /proc and /sys that are safe (and useful) to snapshot on every run. */
    private static final String[] KERNEL_PATHS = {
            "/proc/version", "/proc/cmdline", "/proc/uptime", "/proc/loadavg",
            "/proc/meminfo", "/proc/vmstat", "/proc/buddyinfo", "/proc/pagetypeinfo",
            "/proc/zoneinfo", "/proc/slabinfo", "/proc/interrupts", "/proc/softirqs",
            "/proc/cpuinfo", "/proc/stat", "/proc/modules", "/proc/mounts",
            "/proc/filesystems", "/proc/devices", "/proc/misc", "/proc/partitions",
            "/proc/diskstats", "/proc/swaps", "/proc/locks", "/proc/keys",
            "/proc/timer_list", "/proc/sched_debug", "/proc/pressure/cpu",
            "/proc/pressure/memory", "/proc/pressure/io",
            "/sys/power/wakeup_sources", "/proc/wakeup_sources",
            "/sys/kernel/debug/wakeup_sources",
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/devices/system/cpu/online",
            "/sys/devices/system/cpu/present",
    };

    private static void collectKernel(Context ctx, Storage.Session s, StringBuilder index,
                                      List<String> warnings, Progress cb) {
        int ok = 0, denied = 0, missing = 0;
        for (String path : KERNEL_PATHS) {
            String name = Util.slug(path.substring(1));
            Shell.Result r = Shell.runReported("cat " + path + " 2>&1", 20000);
            String body = r.text();

            boolean deniedHit = body.contains("Permission denied")
                    || body.contains("Operation not permitted")
                    || body.contains("No such file or directory");
            if (body.length() == 0 || deniedHit) {
                if (body.contains("No such file")) missing++;
                else denied++;
                index.append("kernel/").append(name).append(" :: ")
                     .append(body.length() == 0 ? "empty" : "unavailable")
                     .append(" (").append(r.modeName()).append(")\n");
                continue;
            }
            Storage.WriteResult w = Storage.writeText(ctx, s, "kernel/" + name,
                    "# " + path + "\n# mode: " + r.modeName() + "\n\n" + body);
            if (w.ok) {
                ok++;
                index.append("kernel/").append(name).append(" :: ok :: ")
                     .append(Util.bytes(body.length())).append("\n");
            } else {
                index.append("kernel/").append(name).append(" :: WRITE FAILED: ").append(w.detail).append("\n");
            }
        }

        // Thermal zones, walked as a set rather than hard-coded indices.
        step(cb, "内核专项", "thermal zones…");
        StringBuilder th = new StringBuilder();
        Shell.Result tz = Shell.runReported(
                "for z in /sys/class/thermal/thermal_zone*; do "
                + "[ -d \"$z\" ] || continue; "
                + "echo \"### $z  type=$(cat $z/type 2>/dev/null)  temp=$(cat $z/temp 2>/dev/null)\"; "
                + "done", 30000);
        if (tz.out.length() > 0) {
            th.append("thermal zones\n=============\n\n").append(tz.out);
            ok++;
        }
        // Cooling devices tell you whether the kernel is actively throttling.
        Shell.Result cd = Shell.runReported(
                "for c in /sys/class/thermal/cooling_device*; do "
                + "[ -d \"$c\" ] || continue; "
                + "echo \"### $c  type=$(cat $c/type 2>/dev/null)  state=$(cat $c/cur_state 2>/dev/null)/$(cat $c/max_state 2>/dev/null)\"; "
                + "done", 30000);
        if (cd.out.length() > 0) {
            th.append("\ncooling devices\n===============\n\n").append(cd.out);
        }
        if (th.length() > 0) {
            Storage.writeText(ctx, s, "kernel/thermal.txt", th.toString());
            // Say WHICH of the two walks produced data: reporting a bare "ok" hid the case where
            // thermal zones exist but cooling devices do not (or vice versa), leaving the reader
            // to assume both were captured.
            index.append("kernel/thermal.txt :: ok (")
                 .append(tz.out.length() > 0 ? "zones" : "no zones")
                 .append(", ")
                 .append(cd.out.length() > 0 ? "cooling devices" : "no cooling devices")
                 .append(")\n");
        } else {
            Storage.writeText(ctx, s, "kernel/thermal.txt",
                    "thermal information NOT AVAILABLE\n"
                  + "=================================\n\n"
                  + "Neither /sys/class/thermal/thermal_zone* nor cooling_device* returned data.\n\n"
                  + "This is normal on devices that expose thermal data elsewhere (Qualcomm uses\n"
                  + "/sys/class/thermal on most builds, but some vendors publish it through a\n"
                  + "thermal HAL only, or restrict it to root).\n\n"
                  + "Alternatives on this device:\n"
                  + "  * dumpsys thermalservice  (see dumpsys/thermalservice.txt)\n"
                  + "  * the kernel log - see kernel_log.txt\n"
                  + "  * with root: /sys/kernel/debug/thermal*\n");
            index.append("kernel/thermal.txt :: NOT AVAILABLE (neither zones nor cooling devices)\n");
        }

        // Regulators are how you catch a panel supply that never came up.
        step(cb, "内核专项", "regulators 与 clocks…");
        Shell.Result reg = Shell.runReported(
                "for r in /sys/class/regulator/regulator.*; do "
                + "[ -d \"$r\" ] || continue; "
                + "echo \"### $r  name=$(cat $r/name 2>/dev/null)  state=$(cat $r/state 2>/dev/null) "
                + "microvolts=$(cat $r/microvolts 2>/dev/null)  mode=$(cat $r/opmode 2>/dev/null)\"; "
                + "done", 40000);
        if (reg.out.length() > 0) {
            Storage.writeText(ctx, s, "kernel/regulators.txt",
                    "regulator state (a panel supply stuck at 0 uV is a common bring-up bug)\n\n" + reg.out);
            index.append("kernel/regulators.txt :: ok :: ").append(Util.bytes(reg.out.length())).append("\n");
        } else {
            index.append("kernel/regulators.txt :: unavailable (").append(reg.modeName()).append(")\n");
        }

        // Debugfs is where the display and DRM drivers keep their state; root only.
        Shell.Result dbg = Shell.runReported("ls /sys/kernel/debug 2>&1 | head -n 60", 20000);
        if (dbg.out.length() > 0 && !dbg.out.contains("Permission denied")) {
            Storage.writeText(ctx, s, "kernel/debugfs_listing.txt",
                    "top-level /sys/kernel/debug entries (a full walk is usually too slow)\n\n" + dbg.out);
            index.append("kernel/debugfs_listing.txt :: ok\n");
        } else {
            index.append("kernel/debugfs_listing.txt :: unavailable (needs root)\n");
        }

        index.append("kernel/ :: ").append(ok).append(" captured, ").append(denied)
             .append(" denied/empty, ").append(missing).append(" absent\n");
        if (denied > 0) {
            warnings.add("内核专项有 " + denied + " 项被拒绝读取，这部分通常需要 root。");
        }
    }

    // ------------------------------------------------------------- bugreport

    private static void collectBugreport(Context ctx, Storage.Session s, StringBuilder index,
                                         List<String> warnings, long remainingMs) {
        long budget = Math.min(Math.max(remainingMs - 15000, 30000), 12 * 60 * 1000L);

        File staging = Storage.stageFile(ctx, "bugreport/bugreport.zip");
        File parent = staging.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        //noinspection ResultOfMethodCallIgnored
        staging.delete();

        // `bugreport <file>` writes a zip on modern builds and a flat text file on old ones.
        Shell.Result r = Shell.runReported("bugreport -p -o '" + Storage.esc(staging.getAbsolutePath()) + "'",
                budget);
        if (!staging.exists() || staging.length() == 0) {
            // Older signature: `bugreport <path>`.
            Shell.Result r2 = Shell.runReported("bugreport '" + Storage.esc(staging.getAbsolutePath()) + "'",
                    Math.max(budget / 2, 30000));
            if (staging.exists() && staging.length() > 0) r = r2;
        }

        if (staging.exists() && staging.length() > 0) {
            Storage.WriteResult w = Storage.copyInto(ctx, s, "bugreport/bugreport.zip", staging);
            index.append("bugreport/bugreport.zip :: ")
                 .append(w.ok ? "ok" : ("FAILED: " + w.detail))
                 .append(" :: ").append(Util.bytes(staging.length()))
                 .append(" (").append(r.modeName()).append(")\n");
            if (!w.ok) warnings.add("bugreport 生成成功但搬运失败：" + w.detail);
            //noinspection ResultOfMethodCallIgnored
            staging.delete();
            return;
        }

        // Fall back to a scripted approximation of bugreport. This is what actually works
        // without root on most production builds, and it covers the same ground.
        StringBuilder br = new StringBuilder();
        br.append("BUGREPORT FALLBACK (scripted)\n");
        br.append("=============================\n\n");
        br.append("`bugreport` itself could not run here (it needs root or a shell uid).\n");
        br.append("command : ").append(r.cmd).append("\n");
        br.append("mode    : ").append(r.modeName()).append("  exit=").append(r.code).append("\n");
        br.append("stderr  : ").append(Util.trimTo(r.err, 3000)).append("\n\n");
        br.append("The equivalent information has been gathered into the other files of this\n");
        br.append("run. This file additionally captures the pieces a bugreport would contain\n");
        br.append("that are not covered elsewhere.\n\n");

        String[][] extra = {
                { "dropbox", "ls -la /data/system/dropbox 2>&1 | head -n 100" },
                { "tombstones", "ls -la /data/tombstones 2>&1 | head -n 100" },
                { "anr_dir", "ls -la /data/anr 2>&1 | head -n 60" },
                { "last_anr", "cat /data/anr/traces.txt 2>&1 | tail -n 400" },
                { "last_kmsg", "cat /proc/last_kmsg 2>&1 | tail -n 400" },
                { "pstore", "ls -la /sys/fs/pstore 2>&1" },
                { "pstore_dmesg", "cat /sys/fs/pstore/dmesg-ramoops-0 2>&1 | tail -n 400" },
                { "reboot_reason", "cat /sys/module/kernel/parameters/panic 2>&1; getprop ro.boot.bootreason" },
                { "mounts", "mount 2>&1" },
                { "df", "df -h 2>&1" },
                { "du_data", "du -sk /data/* 2>/dev/null | sort -n | tail -n 40" },
                { "top", "top -n 1 -b 2>&1 | head -n 60" },
                { "processes", "ps -A 2>&1" },
                { "services", "service list 2>&1" },
                { "dumpsys_diskstats", "dumpsys diskstats 2>&1" },
                { "selinux", "getenforce 2>&1; cat /sys/fs/selinux/enforce 2>&1" },
                { "uptime_since", "uptime 2>&1" },
                { "kernel_warnings", "dmesg 2>&1 | grep -iE 'warn|error|fail|panic|bug|oops' | tail -n 300" },
        };

        int got = 0;
        for (String[] e : extra) {
            Shell.Result er = Shell.runReported(e[1], 45000);
            br.append("\n---------------- ").append(e[0]).append(" ----------------\n");
            br.append("$ ").append(e[1]).append("\n");
            br.append("[mode=").append(er.modeName()).append(" exit=").append(er.code).append("]\n");
            if (er.out.length() > 0) {
                br.append(Util.trimTo(er.out, 400000)).append("\n");
                got++;
            } else {
                br.append("(no output)").append(er.err.length() > 0 ? " :: " + firstLine(er.err) : "").append("\n");
            }
        }

        Storage.WriteResult w = Storage.writeText(ctx, s, "bugreport/bugreport_fallback.txt", br.toString());
        index.append("bugreport/bugreport_fallback.txt :: ")
             .append(w.ok ? ("ok (" + got + "/" + extra.length + " sections)") : ("FAILED: " + w.detail))
             .append("\n");
        warnings.add("bugreport 未能以标准方式生成，已改用脚本近似版本（"
                + got + "/" + extra.length + " 段有数据）。");
    }

    // ---------------------------------------------------------------- helper

    private static void step(Progress cb, String phase, String detail) {
        try { cb.onStep(phase, detail); } catch (Throwable ignored) { }
    }

    private static String firstLine(String s) {
        if (s == null || s.length() == 0) return "";
        int i = s.indexOf('\n');
        String l = i < 0 ? s : s.substring(0, i);
        return l.length() > 200 ? l.substring(0, 200) : l.trim();
    }
}
