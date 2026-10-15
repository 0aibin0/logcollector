package com.dsh.logcollector;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies a finished collection run and writes {@code verify.txt}.
 *
 * <p>The problem this solves: a run can "succeed" while individual artefacts are empty, truncated,
 * or full of a permission-denial message instead of data. Emitting 150 files where a third are
 * 28-byte stubs is not a useful collection - and on a new device nobody knows which files to
 * trust.
 *
 * <p>So every run is audited afterwards and each artefact is placed in one of five states:
 * <ul>
 *   <li><b>OK</b> - has real content and looks like text/log output.</li>
 *   <li><b>THIN</b> - exists but is suspiciously small (under {@link #MIN_USEFUL_BYTES}).</li>
 *   <li><b>PLACEHOLDER</b> - deliberately written to explain a missing capability (for example
 *       {@code dmesg.txt} on a seccomp-blocked device). Not a failure, but not data either.</li>
 *   <li><b>EMPTY</b> - zero bytes. Always a defect worth reporting.</li>
 *   <li><b>MISSING</b> - expected but absent.</li>
 * </ul>
 *
 * <p>The report ends with a tally and an explicit list of what is missing and why, so the answer
 * to "did this run actually capture what I need?" is one file, not a directory safari.
 */
final class Verifier {

    private Verifier() { }

    /** Below this, a text artefact is treated as suspicious rather than useful. */
    private static final int MIN_USEFUL_BYTES = 128;

    /** Artefacts every run is expected to produce, with the reason each one matters. */
    private static final String[][] EXPECTED = {
            // path fragment,                               required, why it matters
            { "capability.txt",         "yes", "device + privilege report" },
            { "logcat_all.txt",         "yes", "all logcat buffers merged" },
            { "logcat_main.txt",        "no",  "main logcat buffer" },
            { "logcat_system.txt",      "no",  "system logcat buffer" },
            { "logcat_crash.txt",       "no",  "crash logcat buffer" },
            { "logcat_events.txt",      "no",  "events buffer (am_crash / am_anr / am_proc_died)" },
            { "logcat_buffer_sizes.txt","no",  "logcat buffer capacities" },
            { "dmesg.txt",              "yes", "kernel ring buffer (may be a placeholder)" },
            { "kernel_log.txt",         "no",  "kernel log via logcat -b kernel" },
            { "cmdline.txt",            "yes", "kernel command line / boot args" },
            { "prop.txt",               "yes", "getprop" },
            { "dumpsys/_service_list.txt", "yes", "the dumpsys service inventory" },
            { "dumpsys_all.txt",        "no",  "every dumpsys service in one stream" },
            { "display/backlight_nodes.txt", "no", "backlight sysfs nodes" },
            { "display/panel_info.txt", "no",  "panel / DRM / DSI info" },
            { "display/brightness_timeline.txt", "no", "brightness node samples" },
            { "display/brightness_from_services.txt", "no", "framework-reported brightness" },
            { "display/brightness_map_extract.txt", "no", "screenBrightnessMap / HBM extract" },
            { "display/panel_ids.txt",  "no",  "panel identifiers" },
            { "prop_sorted.txt",        "no",  "getprop reformatted as key=value" },
            { "prop_selected.txt",      "no",  "curated boot/build properties" },
            { "kernel/proc_meminfo",    "no",  "memory info" },
            { "kernel/thermal.txt",     "no",  "thermal zones" },
            { "media/screenshot.png",   "no",  "screen capture" },
    };

    /**
     * Artefacts that are written AFTER this audit runs, so their absence is not a defect.
     *
     * <p>{@code index.txt} is written after verification (it reports the verified tallies, so it
     * cannot exist yet), and {@code verify.txt} is this report - writing itself. Auditing either
     * one produced a bogus "MISSING" that made every run look INCOMPLETE. Observed on a real
     * device before this list existed.
     */
    private static final String[] WRITTEN_AFTER_AUDIT = { "index.txt", "verify.txt" };

    /** Text that means "this is an explanation, not data". */
    private static final String[] PLACEHOLDER_MARKERS = {
            "NOT AVAILABLE",
            "NOT PRODUCED",
            "NOT CAPTURED",
            "SKIPPED",
            "DENIED",
            "unavailable",
    };

    /** Phrases that indicate a permission or SELinux refusal leaked into the file body. */
    private static final String[] DENIAL_MARKERS = {
            "Permission denied",
            "Permission Denial",
            "Operation not permitted",
            "SELinux",
            "avc: denied",
    };

    /**
     * How far into a file a marker may appear and still classify the whole file as an explanation.
     *
     * <p>This window is the difference between a useful self-check and a misleading one. A file the
     * app wrote as an explanation announces itself in its FIRST LINES ("dmesg NOT AVAILABLE",
     * "kernel_log.txt not used on this device"). Real captured data, by contrast, mentions denials
     * incidentally and far too often to be a signal: a multi-megabyte logcat legitimately contains
     * {@code avc: denied} records for other processes, and {@code capability.txt} quotes the su
     * error in its privileges section.
     *
     * <p>Scanning the whole head instead of just the opening mislabelled both - a 4.8 MB logcat
     * capture and an 11 KB events dump were reported as "contains a refusal, not data", which
     * tells the reader to ignore real logs. Observed on a real device.
     */
    private static final int MARKER_WINDOW_CHARS = 600;

    /**
     * Files at or above this size are never treated as a placeholder.
     *
     * <p>Belt and braces alongside {@link #MARKER_WINDOW_CHARS}: a genuine explanation is a few
     * hundred bytes to a few kilobytes, never megabytes.
     */
    private static final int MARKER_SCAN_LIMIT_BYTES = 64 * 1024;

    /** Single line result for one artefact. */
    private static final class Item {
        String path = "";
        String state = "OK";
        long bytes;
        String note = "";
        boolean required;
    }

    /**
     * Audits {@code dir} and writes {@code verify.txt} into it.
     *
     * @return the number of artefacts that need attention (EMPTY/MISSING/PLACEHOLDER/THIN),
     *         or -1 if the report itself could not be written
     */
    static int verify(Context ctx, Storage.Session s, Collector.Report report) {
        List<Item> items = new ArrayList<Item>();

        for (String[] spec : EXPECTED) {
            Item it = new Item();
            it.path = spec[0];
            it.required = "yes".equals(spec[1]);
            auditFile(new File(s.dir, spec[0]), it, spec[2]);
            items.add(it);
        }

        // Everything else the run produced, so the audit covers the whole tree rather than a
        // hand-maintained list that will drift.
        List<File> all = new ArrayList<File>();
        walk(s.dir, all);
        for (File f : all) {
            String rel = relativize(s.dir, f);
            boolean skip = false;
            for (String later : WRITTEN_AFTER_AUDIT) if (rel.equals(later)) skip = true;
            if (skip) continue;
            boolean known = false;
            for (Item i : items) if (i.path.equals(rel)) { known = true; break; }
            if (known) continue;
            Item it = new Item();
            it.path = rel;
            auditFile(f, it, "additional artefact");
            items.add(it);
        }

        // Artefacts the collectors REPORTED but did not write. Without this the audit could only
        // ever see what exists, so a collector that silently skipped its output looked fine.
        if (report != null && !report.gaps.isEmpty()) {
            for (String[] gap : report.gaps) {
                String path = gap.length > 0 ? gap[0] : "(unspecified)";
                String why = gap.length > 1 ? gap[1] : "not produced";
                boolean known = false;
                for (Item i : items) if (i.path.equals(path)) { known = true; break; }
                if (known) continue;
                Item it = new Item();
                it.path = path;
                it.state = "MISSING";
                it.note = why;
                items.add(it);
            }
        }

        // ------------------------------------------------------------- tally
        int ok = 0, thin = 0, placeholder = 0, empty = 0, missing = 0;
        int totalBytes = 0;
        List<Item> attention = new ArrayList<Item>();
        for (Item i : items) {
            totalBytes += i.bytes;
            if ("OK".equals(i.state)) ok++;
            else {
                if ("THIN".equals(i.state)) thin++;
                else if ("PLACEHOLDER".equals(i.state)) placeholder++;
                else if ("EMPTY".equals(i.state)) empty++;
                else if ("MISSING".equals(i.state)) missing++;
                attention.add(i);
            }
        }

        // ------------------------------------------------------------ report
        StringBuilder sb = new StringBuilder();
        sb.append("SELF-CHECK - is this run actually usable?\n");
        sb.append("=========================================\n\n");
        sb.append("run dir   : ").append(s.dir.getAbsolutePath()).append("\n");
        sb.append("checked   : ").append(Util.human()).append("\n");
        sb.append("artefacts : ").append(items.size())
          .append("  (").append(Util.bytes(totalBytes)).append(" total)\n");
        sb.append("\n");
        sb.append("OK          : ").append(ok).append("   has real content\n");
        sb.append("PLACEHOLDER : ").append(placeholder).append("   explains a capability this device denies (not a bug)\n");
        sb.append("THIN        : ").append(thin).append("   exists but under ")
          .append(MIN_USEFUL_BYTES).append(" bytes - probably not useful\n");
        sb.append("EMPTY       : ").append(empty).append("   zero bytes - a defect\n");
        sb.append("MISSING     : ").append(missing).append("   expected but not produced\n");
        sb.append("\n");

        // ------------------------------------------------------- verdict
        boolean requiredBad = false;
        for (Item i : items) {
            if (i.required && !"OK".equals(i.state) && !"PLACEHOLDER".equals(i.state)) {
                requiredBad = true;
                break;
            }
        }
        sb.append("--- verdict --------------------------------------------------------\n");
        if (requiredBad) {
            sb.append("INCOMPLETE - a required artefact is missing or empty. See the list below.\n");
        } else if (empty > 0 || missing > 0) {
            sb.append("USABLE WITH GAPS - every required artefact is present, but some optional\n");
            sb.append("ones are missing or empty. The gaps are listed below with their cause.\n");
        } else {
            sb.append("GOOD - every required artefact is present and non-empty.\n");
        }
        sb.append("\n");

        // -------------------------------------------- what needs attention
        if (!attention.isEmpty()) {
            sb.append("--- needs attention -------------------------------------------------\n");
            sb.append(String.format("%-9s %-46s %10s  %s%n", "STATE", "FILE", "BYTES", "WHY"));
            for (Item i : attention) {
                sb.append(String.format("%-9s %-46s %10d  %s%n",
                        i.state, truncate(i.path, 44), i.bytes, i.note));
            }
            sb.append("\n");
        }

        // ------------------------------------------ can the logs be read?
        sb.append("--- readability check ----------------------------------------------\n");
        sb.append("Each text artefact was opened and its first bytes inspected. A file that\n");
        sb.append("fails here would open as garbage or as a permission error in an editor.\n\n");
        int readable = 0, unreadable = 0;
        StringBuilder unread = new StringBuilder();
        for (Item i : items) {
            File f = new File(s.dir, i.path);
            if (!f.isFile() || f.length() == 0) continue;
            if (isTextArtifact(i.path)) {
                String head = readHead(f, 4096);
                if (head == null) {
                    unreadable++;
                    unread.append("  UNREADABLE  ").append(i.path).append("\n");
                } else if (looksBinary(head)) {
                    unreadable++;
                    unread.append("  BINARY?     ").append(i.path)
                          .append("  (contains NUL bytes; expected for zips/mp4)\n");
                } else {
                    readable++;
                }
            }
        }
        sb.append("  text artefacts readable : ").append(readable).append("\n");
        sb.append("  not readable / binary   : ").append(unreadable).append("\n");
        if (unread.length() > 0) sb.append("\n").append(unread);

        // --------------------------------------- how to fill the gaps
        sb.append("\n--- how to fill the gaps -------------------------------------------\n");
        // Group by hint instead of repeating the same paragraph per file. On a locked-down device
        // this section used to run to hundreds of identical lines, which buried the two or three
        // pieces of advice that actually differed.
        java.util.LinkedHashMap<String, List<String>> byHint =
                new java.util.LinkedHashMap<String, List<String>>();
        for (Item i : items) {
            if ("OK".equals(i.state)) continue;
            String hint = hintFor(i.path);
            if (hint == null) continue;
            List<String> bucket = byHint.get(hint);
            if (bucket == null) { bucket = new ArrayList<String>(); byHint.put(hint, bucket); }
            bucket.add(i.path + " [" + i.state + "]");
        }
        if (byHint.isEmpty()) {
            sb.append("* nothing missing - this run needs no follow-up.\n");
        } else {
            for (java.util.Map.Entry<String, List<String>> e : byHint.entrySet()) {
                List<String> paths = e.getValue();
                sb.append("\n* ").append(paths.size()).append(" artefact(s): ")
                  .append(joinFirst(paths, 6)).append("\n");
                for (String line : e.getKey().split("\n")) sb.append("  ").append(line).append("\n");
            }
        }
        sb.append("\n  General fallback, which needs no root and no device changes:\n");
        sb.append("    pwsh -File tools/augment_from_pc.ps1\n");
        sb.append("  It runs the same commands through adb, where seccomp, SELinux and the\n");
        sb.append("  app permission model do not apply, and writes *_from_pc.txt files into\n");
        sb.append("  this same directory.\n");

        Storage.WriteResult w = Storage.writeText(ctx, s, "verify.txt", sb.toString());
        if (!w.ok) return -1;

        if (report != null) {
            report.verified = true;
            report.okCount = ok;
            report.gapCount = thin + placeholder + empty + missing;
            if (requiredBad) {
                report.warnings.add("自检：必需产物缺失或为空，详见 verify.txt");
            }
        }
        return thin + placeholder + empty + missing;
    }

    // ------------------------------------------------------------------ audit

    private static void auditFile(File f, Item it, String why) {
        if (!f.exists()) {
            it.state = "MISSING";
            it.note = "expected artefact was not produced (" + why + ")";
            return;
        }
        if (f.isDirectory()) {
            it.state = "OK";
            it.note = "directory";
            return;
        }
        it.bytes = f.length();
        if (it.bytes == 0) {
            it.state = "EMPTY";
            it.note = "zero bytes - the command ran but produced nothing";
            return;
        }

        // A placeholder or a leaked denial is "explained absence", not data - but the markers are
        // only trusted in the OPENING of the file and only for files small enough to plausibly BE
        // an explanation. See MARKER_WINDOW_CHARS / MARKER_SCAN_LIMIT_BYTES for why.
        if (it.bytes < MARKER_SCAN_LIMIT_BYTES) {
            String head = readHead(f, 2048);
            if (head != null) {
                String opening = head.length() > MARKER_WINDOW_CHARS
                        ? head.substring(0, MARKER_WINDOW_CHARS) : head;
                // Strip the app's own header block so a genuine capture is judged on its DATA.
                String subject = stripHeader(opening);
                for (String m : DENIAL_MARKERS) {
                    if (subject.contains(m)) {
                        it.state = "PLACEHOLDER";
                        it.note = "contains a permission/SELinux refusal, not data: " + firstMarker(subject, m);
                        return;
                    }
                }
                for (String m : PLACEHOLDER_MARKERS) {
                    if (subject.contains(m)) {
                        it.state = "PLACEHOLDER";
                        it.note = "explains an unavailable capability: " + firstMarker(subject, m);
                        return;
                    }
                }
            }
        }

        if (it.bytes < MIN_USEFUL_BYTES) {
            it.state = "THIN";
            it.note = "only " + it.bytes + " bytes - likely a stub, not real output";
            return;
        }
        it.state = "OK";
        it.note = why;
    }

    /**
     * Removes the app's own leading {@code #} header block so a capture is judged on its data.
     *
     * <p>Every artefact this app writes opens with comment lines describing what it is. Those
     * lines legitimately discuss permissions ("needs READ_LOGS", "SELinux proc_cmdline"), and
     * counting them as evidence of a refusal would mislabel every well-formed file that happens
     * to carry an explanatory header.
     *
     * <p>The header ends at the first line that is not blank and not a {@code #} comment; that
     * line and everything after it is the data under test.
     */
    private static String stripHeader(String text) {
        if (text == null) return "";
        int i = 0;
        int n = text.length();
        while (i < n) {
            int eol = text.indexOf('\n', i);
            if (eol < 0) eol = n;
            String line = text.substring(i, eol).trim();
            if (line.length() > 0 && line.charAt(0) != '#') break;
            i = eol + 1;
        }
        return i >= n ? "" : text.substring(i);
    }

    /** A short quote around a marker so the report shows the actual text. */
    private static String firstMarker(String text, String marker) {
        int i = text.indexOf(marker);
        if (i < 0) return marker;
        int start = Math.max(0, i - 30);
        int end = Math.min(text.length(), i + marker.length() + 50);
        String s = text.substring(start, end).replace('\n', ' ').trim();
        return "\"" + (start > 0 ? "..." : "") + s + (end < text.length() ? "..." : "") + "\"";
    }

    private static boolean isTextArtifact(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".zip") || p.endsWith(".mp4")) return false;
        // .png is binary, but a screenshot is worth verifying exists; skip a NUL check on it.
        return true;
    }

    private static boolean looksBinary(String head) {
        int nul = 0;
        int n = Math.min(head.length(), 1024);
        for (int i = 0; i < n; i++) if (head.charAt(i) == 0) nul++;
        return nul > 4;
    }

    private static String readHead(File f, int max) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[max];
            int n = in.read(buf);
            if (n <= 0) return "";
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            Util.close(in);
        }
    }

    /** Lists the first {@code n} paths, then "... and N more". */
    private static String joinFirst(List<String> paths, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paths.size() && i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(paths.get(i));
        }
        if (paths.size() > n) sb.append(", ... and ").append(paths.size() - n).append(" more");
        return sb.toString();
    }

    private static String hintFor(String path) {
        if (path.startsWith("dmesg")) {
            return "dmesg needs root or adb. On Android 11+ the app is denied the syslog\n"
                 + "  syscall by seccomp, so this is expected on most devices. kernel_log.txt\n"
                 + "  (logcat -b kernel) carries the same kernel ring buffer and needs no root.";
        }
        if (path.startsWith("logcat")) {
            return "Logcat needs the READ_LOGS permission:\n"
                 + "    adb shell pm grant com.dsh.logcollector android.permission.READ_LOGS";
        }
        if (path.startsWith("dumpsys")) {
            // A "NOT AVAILABLE - produced no output" stub on a service that DOES refuse is a
            // permission matter; but many services legitimately print nothing on a healthy
            // device (see THIN notes). Point at index.txt so the reader can tell which is which
            // instead of assuming every stub needs a grant.
            return "Check index.txt for this service's outcome line. A 'DENIED (needs X)' line\n"
                 + "  names the exact permission; a 'NO SUCH SERVICE' or 'EMPTY' line means the\n"
                 + "  service simply has nothing to report on this build and needs no grant.\n"
                 + "  Broad grants that unlock dumpsys on a userdebug build:\n"
                 + "    adb shell pm grant com.dsh.logcollector android.permission.DUMP\n"
                 + "    adb shell appops set --uid com.dsh.logcollector GET_USAGE_STATS allow";
        }
        if (path.startsWith("media/screenshot")) {
            return "Screenshot needs root, or a one-time MediaProjection consent from the app\n"
                 + "  UI (tap 'grant screenshot' on the Collect tab).";
        }
        if (path.startsWith("media/screenrecord")) {
            return "Screen recording needs the screenrecord binary and usually root; some\n"
                 + "  SELinux policies block it for apps entirely.";
        }
        if (path.startsWith("bugreport")) {
            return "A full bugreport needs root. Without it the scripted fallback is written\n"
                 + "  instead (bugreport_fallback.txt).";
        }
        if (path.startsWith("kernel/")) {
            return "Kernel sysfs/procfs entries differ per device and vendor; entries that do\n"
                 + "  not exist here simply are not present on this SoC. Root widens this a lot.";
        }
        if (path.startsWith("display/")) {
            return "Display nodes are vendor-specific. If the backlight/panel nodes are absent,\n"
                 + "  check display/panel_info.txt and the kernel log for the bound driver.";
        }
        return "Try the pc-side helper:  pwsh -File tools/augment_from_pc.ps1";
    }

    // ------------------------------------------------------------------ util

    private static void walk(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) walk(k, out);
            else if (k.isFile()) out.add(k);
        }
    }

    private static String relativize(File root, File f) {
        String r = root.getAbsolutePath();
        String a = f.getAbsolutePath();
        if (a.startsWith(r)) {
            String s = a.substring(r.length());
            while (s.startsWith("/") || s.startsWith("\\")) s = s.substring(1);
            return s.replace('\\', '/');
        }
        return f.getName();
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : ("..." + s.substring(s.length() - (n - 3)));
    }
}
