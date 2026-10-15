package com.dsh.logcollector;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Display / backlight / panel snapshot.
 *
 * <p>This is the specialist collector for panel bring-up work, where the interesting state lives
 * in places a generic dump never looks: the sysfs backlight and LED nodes, the vendor
 * {@code display_id_*.xml} configuration, the DRM/DSI debugfs entries, and the
 * display-related binder services.
 */
public final class DisplayCollector {

    private DisplayCollector() { }

    // The backlight sysfs roots are enumerated by the shell scan below, not from Java, because
    // the useful information (which nodes exist, and their values) can only be obtained by
    // walking sysfs on the device itself.

    public static void collect(Context ctx, Storage.Session s, StringBuilder index, Capabilities caps) {
        collectBacklightNodes(ctx, s, index);
        collectBrightnessFromServices(ctx, s, index);
        collectDisplayConfig(ctx, s, index);
        collectPanelInfo(ctx, s, index);
        collectDisplayServices(ctx, s, index);
        collectBrightnessTimeline(ctx, s, index);
    }

    /**
     * Brightness as the framework reports it, which needs no root.
     *
     * <p>The sysfs backlight nodes are unreachable for an app uid on most devices (SELinux
     * {@code vendor_sysfs_graphics} plus a {@code root:system} mode on {@code brightness}), so on
     * an unrooted phone this is the only brightness source that actually works. It is also the
     * value the user sees, which makes it the right thing to compare a sysfs node against.
     */
    private static void collectBrightnessFromServices(Context ctx, Storage.Session s, StringBuilder index) {
        StringBuilder sb = new StringBuilder();
        sb.append("brightness as reported by the framework (no root needed)\n");
        sb.append("=======================================================\n");
        sb.append("collected : ").append(Util.human()).append("\n\n");

        String[][] probes = {
                { "display_brightness", "dumpsys display | grep -i -E 'brightness|backlight|nit' | head -n 60",
                  "DisplayManagerService brightness state" },
                { "power_display",      "dumpsys power | grep -i -E 'brightness|screen|display power' | head -n 60",
                  "PowerManagerService display power state" },
                { "settings_brightness","settings get system screen_brightness; "
                                        + "settings get system screen_brightness_mode; "
                                        + "settings get system screen_auto_brightness_adj",
                  "the raw Settings values the framework reads/writes" },
                { "lights_service",     "dumpsys lights | head -n 40", "lights HAL service" },
        };

        int got = 0;
        for (String[] p : probes) {
            Shell.Result r = Shell.runReported(p[1], 30000);
            sb.append("---------------- ").append(p[0]).append(" ----------------\n");
            sb.append("# ").append(p[2]).append("\n");
            sb.append("$ ").append(p[1]).append("\n");
            sb.append("[mode=").append(r.modeName()).append(" exit=").append(r.code).append("]\n");
            if (r.out.trim().length() > 0 && !looksLikeDenial(r.out)) {
                sb.append(Util.trimTo(r.out, 40000)).append("\n");
                got++;
            } else {
                sb.append("(no data)").append(r.err.length() > 0 ? " :: " + firstLine(r.err) : "").append("\n");
            }
            sb.append("\n");
        }

        Storage.WriteResult w = Storage.writeText(ctx, s, "display/brightness_from_services.txt", sb.toString());
        index.append("display/brightness_from_services.txt :: ")
             .append(w.ok ? ("ok (" + got + "/" + probes.length + " sources had data)")
                          : ("FAILED: " + w.detail))
             .append("\n");
    }

    // ------------------------------------------------------------ backlight

    private static void collectBacklightNodes(Context ctx, Storage.Session s, StringBuilder index) {
        StringBuilder sb = new StringBuilder();
        sb.append("backlight / LED node snapshot\n");
        sb.append("=============================\n");
        sb.append("collected : ").append(Util.human()).append("\n\n");

        // Per-section outcomes, reported in ONE index line at the end (see below).
        String backlightSection;
        String ledSection;
        String deepSection;

        // Enumerate every backlight device and read all of its attributes.
        Shell.Result r = Shell.runReported(
                "for d in /sys/class/backlight/*; do "
                + "  [ -d \"$d\" ] || continue; "
                + "  echo \"########## $d\"; "
                + "  echo \"--- resolve: $(ls -l $d 2>/dev/null | tail -n +2 | head -n 3)\"; "
                + "  for a in \"$d\"/*; do "
                + "    [ -f \"$a\" ] || continue; "
                + "    n=$(basename \"$a\"); "
                + "    case \"$n\" in "
                + "      uevent|subsystem|*_link) continue;; "
                + "    esac; "
                + "    v=$(cat \"$a\" 2>&1 | head -c 4000); "
                + "    echo \"  $n = $v\"; "
                + "  done; "
                + "done", 45000);

        if (r.out.trim().length() > 0 && !r.out.contains("No such file")) {
            sb.append("--- /sys/class/backlight --------------------------------------------\n");
            sb.append(r.out).append("\n");
            backlightSection = "ok :: " + Util.bytes(r.out.length());
        } else {
            sb.append("--- /sys/class/backlight --------------------------------------------\n");
            sb.append("no backlight attributes were readable (mode=").append(r.modeName())
              .append(" exit=").append(r.code).append(")\n\n");
            // Explain the common cause instead of leaving a bare exit code. On the reference
            // device /sys/class/backlight/panel0-backlight IS present as a symlink, but the app
            // cannot follow it: the target directory is labelled vendor_sysfs_graphics, which
            // untrusted_app may not traverse, and the brightness node itself is mode
            // -rw-rw---- system:system. So an empty result here is EXPECTED for an unprivileged
            // app even though root sees the node immediately.
            sb.append("Note: the backlight class may exist but be unreachable from this app.\n");
            sb.append("  Typical causes, in order of likelihood:\n");
            sb.append("    1. SELinux - the node lives under vendor_sysfs_graphics, which an\n");
            sb.append("       untrusted_app domain may not traverse. `ls -laZ` the path as root to\n");
            sb.append("       confirm; the label appears on the target of the backlight symlink.\n");
            sb.append("    2. DAC - brightness is commonly mode -rw-rw---- root:system, so an app\n");
            sb.append("       uid that is in neither group gets EACCES even with SELinux permissive.\n");
            sb.append("    3. genuinely no backlight class (rare on a phone).\n");
            sb.append("  What this means: brightness nodes are a root-only source on most devices.\n");
            sb.append("  The HAL-reported brightness is still available without root via\n");
            sb.append("  dumpsys display / dumpsys power (see dumpsys/), and the kernel log\n");
            sb.append("  (kernel_log.txt) records the panel driver's own brightness programming.\n");
            sb.append("  Use tools/augment_from_pc.ps1 to read these nodes as root.\n\n");
            backlightSection = "unavailable ("
                    + (r.out.contains("No such file") ? "no backlight class on this device"
                                                      : "not readable by an app uid - needs root")
                    + ")";
        }

        // LED-class devices carry the panel and key backlights on many QCOM/MTK builds.
        Shell.Result leds = Shell.runReported(
                "for d in /sys/class/leds/*; do "
                + "  [ -d \"$d\" ] || continue; "
                + "  echo \"########## $d\"; "
                + "  for a in brightness max_brightness actual_brightness device_name led_mode; do "
                + "    [ -f \"$d/$a\" ] || continue; "
                + "    echo \"  $a = $(cat $d/$a 2>&1 | head -c 2000)\"; "
                + "  done; "
                + "done", 45000);
        if (leds.out.trim().length() > 0) {
            sb.append("\n--- /sys/class/leds ------------------------------------------------\n");
            sb.append(leds.out).append("\n");
            ledSection = "ok :: " + Util.bytes(leds.out.length());
        } else {
            ledSection = "unavailable on this build (no LED-class nodes readable)";
        }

        // Direct scan for anything brightness-shaped outside the standard classes.
        //
        // `find -maxdepth` is NOT portable: toybox only gained it in Android 8, and busybox
        // builds on older ROMs reject it outright, which would silently return nothing. A shell
        // glob walk works everywhere and needs no find at all.
        //
        // DEPTH: /sys/class/backlight entries are symlinks, so a real brightness node lives at
        // /sys/devices/platform/soc/<addr>.<bus>/<addr>.<dev>/backlight/<name>/brightness -
        // measured at 7-8 levels below /sys/devices on the test device. A fixed limit is a
        // portability trap in both directions (too small silently finds nothing; too large walks
        // a huge tree on every run), so the scan runs with a generous depth and stops early once
        // it has collected enough lines.
        Shell.Result deep = Shell.runReported(
                "scan() { d=$1; n=$2; [ $n -gt 0 ] || return 0; "
                + "for p in \"$d\"/*; do [ -e \"$p\" ] || continue; "
                + "  b=$(basename \"$p\"); "
                + "  case \"$b\" in brightness|bl_level|*backlight*|disp_*) "
                + "    if [ -f \"$p\" ]; then echo \"$p = $(cat \"$p\" 2>&1 | head -c 200)\"; "
                + "    else echo \"$p/ (dir)\"; fi ;; esac; "
                + "  [ -d \"$p\" ] && scan \"$p\" $((n-1)); "
                + "done; }; "
                + "scan /sys/devices 12 2>/dev/null | head -n 200", 150000);
        if (deep.out.trim().length() > 0) {
            sb.append("\n--- non-standard brightness paths ----------------------------------\n");
            sb.append(deep.out).append("\n");
            deepSection = "ok :: " + Util.bytes(deep.out.length());
        } else {
            deepSection = "unavailable (deep scan found no non-standard brightness paths)";
        }

        // ONE file, and the index names that one file.
        //
        // The LED and deep-scan sections used to be appended to index.txt under their own names
        // ("display/led_nodes.txt", "display/backlight_deep_scan.txt") while their CONTENT was
        // written into display/backlight_nodes.txt. Nothing ever created those two paths, so
        // index.txt advertised files that do not exist - and the Verifier could not catch it,
        // because it audits what is on disk rather than what the manifest claims.
        sb.append("\n--- section summary ------------------------------------------------\n");
        sb.append("  /sys/class/backlight : ").append(backlightSection).append("\n");
        sb.append("  /sys/class/leds      : ").append(ledSection).append("\n");
        sb.append("  deep scan            : ").append(deepSection).append("\n");

        Storage.WriteResult w = Storage.writeText(ctx, s, "display/backlight_nodes.txt", sb.toString());
        if (!w.ok) {
            index.append("display/backlight_nodes.txt :: WRITE FAILED: ").append(w.detail).append("\n");
        } else {
            index.append("display/backlight_nodes.txt :: ok :: ")
                 .append(Util.bytes(sb.length()))
                 .append("  (contains backlight + leds + deep scan: ")
                 .append(backlightSection).append("; ").append(ledSection).append("; ")
                 .append(deepSection).append(")\n");
        }
    }

    // -------------------------------------------------------- display config

    /**
     * The vendor display configuration. On the panel bring-up builds this is where
     * {@code screenBrightnessMap} and the HBM transition point are declared, so a copy of it in
     * every log run makes brightness bugs reproducible after the fact.
     */
    private static void collectDisplayConfig(Context ctx, Storage.Session s, StringBuilder index) {
        String[] dirs = {
                "/vendor/etc/displayconfig",
                "/odm/etc/displayconfig",
                "/system/etc/displayconfig",
                "/vendor/etc/display",
                "/vendor/etc/panel",
        };

        StringBuilder sb = new StringBuilder();
        sb.append("vendor display configuration\n");
        sb.append("============================\n\n");

        Shell.Result ls = Shell.runReported("ls -la /vendor/etc/displayconfig 2>&1", 20000);
        if (ls.out.trim().length() > 0) {
            sb.append("--- listing --------------------------------------------------------\n");
            sb.append(ls.out).append("\n");
        }

        int found = 0;
        for (String dir : dirs) {
            Shell.Result r = Shell.runReported(
                    "for f in " + dir + "/*.xml " + dir + "/*.conf " + dir + "/*.json; do "
                    + "  [ -f \"$f\" ] || continue; "
                    + "  echo \"########## $f\"; "
                    + "  cat \"$f\" 2>&1; "
                    + "  echo; "
                    + "done", 40000);
            if (r.out.contains("##########")) {
                sb.append("\n--- ").append(dir).append(" ---\n");
                sb.append(r.out).append("\n");
                found++;
            }
        }

        if (found > 0) {
            Storage.WriteResult w = Storage.writeText(ctx, s, "display/displayconfig.xml", sb.toString());
            index.append("display/displayconfig.xml :: ").append(w.ok ? ("ok (" + found + " dirs)")
                    : ("FAILED: " + w.detail)).append("\n");

            // A focused extract: the brightness map is the single most bug-prone part.
            Shell.Result map = Shell.runReported(
                    "grep -n -A 60 -i 'screenBrightnessMap\\|brightnessMap\\|highBrightnessMode\\|transitionPoint\\|nits' "
                    + "/vendor/etc/displayconfig/*.xml /odm/etc/displayconfig/*.xml 2>/dev/null | head -n 400",
                    30000);
            if (map.out.trim().length() > 0 && !looksLikeDenial(map.out)) {
                Storage.writeText(ctx, s, "display/brightness_map_extract.txt",
                        "brightness map extract from the vendor display config\n"
                        + "(screenBrightnessMap <value>/<nits> must be non-decreasing or the whole\n"
                        + " table is discarded -> getBrightnessFromBacklight NPE -> system_server crash)\n\n"
                        + map.out);
                index.append("display/brightness_map_extract.txt :: ok\n");
            } else {
                // Always leave a file, never just an index line: a reader who opens
                // display/ and finds nothing cannot tell "no brightness map here" from
                // "the tool forgot to look".
                String why = looksLikeDenial(map.out)
                        ? "the display config could not be read (permission denied)"
                        : "no screenBrightnessMap / HBM / transitionPoint entries were found";
                Storage.writeText(ctx, s, "display/brightness_map_extract.txt",
                        "brightness map extract - NOT AVAILABLE\n"
                      + "=====================================\n\n"
                      + why + "\n\n"
                      + "Why this file matters:\n"
                      + "  screenBrightnessMap <value>/<nits> entries must be non-decreasing. If they\n"
                      + "  are not, DisplayDeviceConfig.loadBrightnessMap discards the ENTIRE table,\n"
                      + "  which leads to getBrightnessFromBacklight NPE and a system_server crash.\n\n"
                      + "Where the full config was searched:\n"
                      + "  /vendor/etc/displayconfig/*.xml\n"
                      + "  /odm/etc/displayconfig/*.xml\n\n"
                      + "See display/displayconfig.xml for whatever WAS readable, and\n"
                      + "display/panel_info.txt for the panel the kernel actually bound.\n\n"
                      + "# raw grep output:\n" + Util.trimTo(map.out, 2000) + "\n");
                index.append("display/brightness_map_extract.txt :: ")
                     .append(looksLikeDenial(map.out) ? "unavailable (permission denied)"
                                                      : "unavailable (no brightness map on this build)")
                     .append("\n");
            }
        } else {
            index.append("display/displayconfig.xml :: not found on this device\n");
        }

        // Per-display identifiers, which is what maps a physical panel to a config file.
        Shell.Result ids = Shell.runReported(
                "for f in /sys/class/drm/*/edid /sys/class/graphics/*/edid /proc/device-tree/*panel*; do "
                + "  [ -e \"$f\" ] || continue; echo \"### $f\"; ls -la \"$f\" 2>&1; done; "
                + "echo '--- device tree panel nodes ---'; "
                + "ls /proc/device-tree 2>&1 | grep -i -E 'panel|dsi|display|lcd' ", 30000);
        if (ids.out.trim().length() > 0 && !looksLikeDenial(ids.out) && ids.out.trim().length() > 64) {
            Storage.writeText(ctx, s, "display/panel_ids.txt", ids.out);
            index.append("display/panel_ids.txt :: ok :: ").append(Util.bytes(ids.out.length())).append("\n");
        } else {
            // Always leave a file - see the brightness_map_extract note above.
            String why = looksLikeDenial(ids.out)
                    ? "the device tree could not be read (permission denied)"
                    : "no EDID or device-tree panel nodes were found";
            Storage.writeText(ctx, s, "display/panel_ids.txt",
                    "panel identifiers - NOT AVAILABLE\n"
                  + "=================================\n\n"
                  + why + "\n\n"
                  + "What was searched:\n"
                  + "  /sys/class/drm/*/edid\n"
                  + "  /sys/class/graphics/*/edid\n"
                  + "  /proc/device-tree/*panel*\n"
                  + "  /proc/device-tree listing filtered for panel|dsi|display|lcd\n\n"
                  + "The panel the kernel actually bound is also recorded in the kernel command\n"
                  + "line (see cmdline.txt, key msm_drm.dsi_display0) and in dmesg / kernel_log.txt.\n\n"
                  + "# raw output:\n" + Util.trimTo(ids.out, 2000) + "\n");
            index.append("display/panel_ids.txt :: ")
                 .append(looksLikeDenial(ids.out) ? "unavailable (permission denied)"
                                                  : "unavailable (no device-tree panel nodes)")
                 .append("\n");
        }
    }

    /**
     * True when a command's captured output is only an error message.
     *
     * <p>Several probes end with {@code 2>&1}, which folds stderr into stdout. The result is a
     * non-empty string that contains no data - and writing it out marked "ok" would present a
     * permission denial to the reader as if it were a configuration capture.
     */
    private static boolean looksLikeDenial(String s) {
        if (s == null || s.length() == 0) return false;
        String t = s.trim();
        // Only treat it as a denial when the text is SHORT: real captures can legitimately
        // mention these words in a value or a log line.
        if (t.length() > 4000) return false;
        return t.contains("Permission denied")
            || t.contains("Operation not permitted")
            || t.contains("No such file or directory")
            || t.contains("Permission Denial")
            || t.contains("not found");
    }

    // ------------------------------------------------------------ panel info

    private static void collectPanelInfo(Context ctx, Storage.Session s, StringBuilder index) {
        StringBuilder sb = new StringBuilder();
        sb.append("panel / DRM / DSI information\n");
        sb.append("=============================\n\n");

        String[][] probes = {
                { "drm_cards", "ls -la /sys/class/drm 2>&1" },
                { "drm_status", "for f in /sys/class/drm/*/status /sys/class/drm/*/modes /sys/class/drm/*/enabled; do "
                                + "[ -f \"$f\" ] || continue; echo \"$f = $(cat $f 2>&1 | head -c 2000)\"; done" },
                { "graphics", "ls -la /sys/class/graphics 2>&1" },
                { "graphics_modes", "for d in /sys/class/graphics/*; do [ -d \"$d\" ] || continue; "
                                    + "echo \"### $d\"; for a in modes mode blank state name; do "
                                    + "[ -f \"$d/$a\" ] || continue; echo \"  $a = $(cat $d/$a 2>&1 | head -c 1000)\"; done; done" },
                { "fb_devices", "ls -la /dev/graphics 2>&1; cat /proc/fb 2>&1" },
                { "dsi_debugfs", "ls -la /sys/kernel/debug/dsi* 2>&1; ls -la /sys/kernel/debug/dri* 2>&1" },
                { "msm_drm", "ls -la /sys/kernel/debug/msm* 2>&1; ls /d/msm* 2>&1" },
                { "disp_debug", "scan() { d=$1; n=$2; [ $n -gt 0 ] || return 0; "
                                + "for p in \"$d\"/*; do [ -e \"$p\" ] || continue; "
                                + "  b=$(basename \"$p\"); "
                                + "  case \"$b\" in *dsi*|*panel*) echo \"$p\" ;; esac; "
                                + "  [ -d \"$p\" ] && scan \"$p\" $((n-1)); "
                                + "done; }; scan /sys/kernel/debug 4 2>/dev/null | head -n 60" },
                { "kernel_panel_log", "dmesg 2>/dev/null | grep -iE 'panel|dsi|drm|backlight|brightness|hbm|lcd|mipi' | tail -n 500" },
                { "dt_panel", "for f in /proc/device-tree/*panel*/*; do echo \"$f = $(cat $f 2>/dev/null | tr -d '\\0' | head -c 200)\"; done 2>/dev/null | head -n 100" },
        };

        for (String[] p : probes) {
            Shell.Result r = Shell.runReported(p[1], 45000);
            sb.append("\n---------------- ").append(p[0]).append(" ----------------\n");
            sb.append("$ ").append(p[1]).append("\n");
            sb.append("[mode=").append(r.modeName()).append(" exit=").append(r.code).append("]\n");
            if (r.out.trim().length() > 0) {
                sb.append(Util.trimTo(r.out, 200000)).append("\n");
            } else {
                sb.append("(no output)").append(r.err.length() > 0 ? " :: " + firstLine(r.err) : "").append("\n");
            }
        }

        Storage.WriteResult w = Storage.writeText(ctx, s, "display/panel_info.txt", sb.toString());
        index.append("display/panel_info.txt :: ").append(w.ok ? "ok" : ("FAILED: " + w.detail)).append("\n");
    }

    // ------------------------------------------------------- display services

    private static void collectDisplayServices(Context ctx, Storage.Session s, StringBuilder index) {
        // Some services are only reachable by their fully qualified name, and a few need extra args.
        String[][] specials = {
                { "SurfaceFlinger", "dumpsys SurfaceFlinger" },
                { "SurfaceFlinger_layers", "dumpsys SurfaceFlinger --list" },
                { "SurfaceFlinger_latency", "dumpsys SurfaceFlinger --latency" },
                { "display", "dumpsys display" },
                { "window_displays", "dumpsys window displays" },
                { "color_display", "dumpsys color_display" },
                { "displayfeature", "dumpsys displayfeature" },
                { "overlay", "dumpsys overlay" },
                { "power", "dumpsys power" },
                { "input_devices", "dumpsys input" },
                { "battery", "dumpsys battery" },
                { "gpu", "dumpsys gpu" },
        };

        int ok = 0, fail = 0;
        StringBuilder summary = new StringBuilder();
        summary.append("display-related service dumps\n=============================\n\n");

        for (String[] sp : specials) {
            Shell.Result r = Shell.runReported(sp[1], 45000);
            String body = r.text();
            if (body.trim().length() == 0) {
                fail++;
                summary.append(String.format("%-26s : EMPTY (%s exit=%d)%n", sp[0], r.modeName(), r.code));
                index.append("display/svc_").append(Util.slug(sp[0])).append(".txt :: empty\n");
                continue;
            }
            Storage.WriteResult w = Storage.writeText(ctx, s, "display/svc_" + Util.slug(sp[0]) + ".txt",
                    "# " + sp[1] + "\n# mode: " + r.modeName() + "  collected: " + Util.human() + "\n\n" + body);
            if (w.ok) {
                ok++;
                summary.append(String.format("%-26s : ok, %s (%s)%n", sp[0], Util.bytes(body.length()), r.modeName()));
                index.append("display/svc_").append(Util.slug(sp[0])).append(".txt :: ok :: ")
                     .append(Util.bytes(body.length())).append("\n");
            } else {
                fail++;
                summary.append(String.format("%-26s : WRITE FAILED %s%n", sp[0], w.detail));
                index.append("display/svc_").append(Util.slug(sp[0])).append(".txt :: WRITE FAILED: ")
                     .append(w.detail).append("\n");
            }
        }

        summary.append("\n").append(ok).append(" captured, ").append(fail).append(" unavailable\n");
        Storage.writeText(ctx, s, "display/summary.txt", summary.toString());
        index.append("display/summary.txt :: ").append(ok).append(" services captured\n");
    }

    // ------------------------------------------------------ brightness timeline

    /**
     * Samples every readable brightness node a few times so a later analysis can tell a stuck
     * node from a node that simply was not read at the right moment.
     */
    private static void collectBrightnessTimeline(Context ctx, Storage.Session s, StringBuilder index) {
        List<String> nodes = new ArrayList<String>();

        Shell.Result find = Shell.runReported(
                "for p in /sys/class/backlight/*/brightness /sys/class/backlight/*/actual_brightness "
                + "/sys/class/backlight/*/max_brightness "
                + "/sys/class/leds/*/brightness /sys/class/leds/*/actual_brightness "
                + "/sys/class/leds/*/max_brightness; do "
                + "  [ -f \"$p\" ] && echo \"$p\"; done", 30000);
        for (String line : find.out.split("\n")) {
            String t = line.trim();
            if (t.length() > 0 && !nodes.contains(t)) nodes.add(t);
        }
        if (nodes.isEmpty()) {
            // Not a silent gap: explain and point at the working alternative.
            Storage.writeText(ctx, s, "display/brightness_timeline.txt",
                    "brightness node timeline - NOT AVAILABLE\n"
                  + "========================================\n\n"
                  + "No brightness nodes under /sys/class/backlight or /sys/class/leds were\n"
                  + "readable by this app, so there was nothing to sample.\n\n"
                  + "This is the normal outcome on a non-rooted device: the nodes exist but the\n"
                  + "app cannot traverse them (SELinux label vendor_sysfs_graphics, and the\n"
                  + "brightness file itself is usually mode -rw-rw---- root:system). See\n"
                  + "display/backlight_nodes.txt for the detailed explanation.\n\n"
                  + "Use display/brightness_from_services.txt instead - it reads the framework's\n"
                  + "own brightness state through dumpsys and settings, which needs no root.\n\n"
                  + "To sample the raw nodes, run as root:\n"
                  + "  pwsh -File tools/augment_from_pc.ps1\n");
            index.append("display/brightness_timeline.txt :: NOT AVAILABLE (no readable nodes; "
                    + "see brightness_from_services.txt)\n");
            return;
        }

        // Cap the node count: each sample is one command, so an unbounded list would turn a
        // 5-second window into minutes on a device with many LEDs.
        final int MAX_NODES = 12;
        if (nodes.size() > MAX_NODES) {
            nodes = new ArrayList<String>(nodes.subList(0, MAX_NODES));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("brightness node timeline\n");
        sb.append("========================\n");
        sb.append("Sampled every 500 ms for 5 s. A node that never changes while the screen\n");
        sb.append("brightness visibly changes is not the node the HAL is driving.\n");
        sb.append("max_brightness is included so a raw node value can be read as a fraction\n");
        sb.append("of full scale.\n\n");

        // Build one command that prints every node as "label<TAB>value", one per line, so a
        // sample costs a single shell spawn instead of one per node. On the test device the
        // per-node version took tens of seconds; this keeps the 11-sample window near its
        // nominal 5 s.
        //
        // `echo` AFTER each value is load-bearing, not cosmetic. A sysfs attribute does not have
        // to end in a newline, and when one does not, `cat` leaves the cursor mid-line so the
        // NEXT label is appended to the previous value - the whole sample then collapses onto a
        // single line and every value after the first is lost. Verified with a replica tree:
        // three no-newline values produced ONE line, "818/tmp/nl/n1\t1234/tmp/nl/n2\t4095".
        // The explicit echo guarantees one "label<TAB>value" line per node regardless.
        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) cmd.append("; ");
            cmd.append("printf '%s\\t' '").append(Storage.esc(nodes.get(i))).append("'; ")
               .append("cat '").append(Storage.esc(nodes.get(i))).append("' 2>/dev/null || echo '-'")
               .append("; echo");
        }

        String header = "t_ms";
        for (String n : nodes) header += "\t" + n;
        sb.append(header).append("\n");

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 11; i++) {
            StringBuilder row = new StringBuilder();
            row.append(System.currentTimeMillis() - t0);
            Shell.Result r = Shell.app(cmd.toString(), 20000);
            String[] values = parseNodeValues(r.out, nodes);
            for (int k = 0; k < nodes.size(); k++) {
                row.append("\t").append(values[k]);
            }
            sb.append(row).append("\n");
            if (i < 10) {
                try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
            }
        }

        sb.append("\nnodes watched:\n");
        for (String n : nodes) sb.append("  ").append(n).append("\n");

        Storage.WriteResult w = Storage.writeText(ctx, s, "display/brightness_timeline.txt", sb.toString());
        index.append("display/brightness_timeline.txt :: ")
             .append(w.ok ? ("ok (" + nodes.size() + " nodes x 11 samples)") : ("FAILED: " + w.detail))
             .append("\n");
    }

    /**
     * Parses {@code "path<TAB>value"} lines into a per-node value array.
     *
     * <p>Matching is positional, so it stays correct even when a {@code /sys/class} symlink
     * resolves to a different path than the one requested.
     *
     * <p>Two failure modes are defended against here, both of which silently produced a table
     * that LOOKED right:
     * <ul>
     *   <li>A value that does not end in a newline used to leave the next label glued to it. The
     *       command now emits an explicit newline per node; if a label still appears inside a
     *       value, the row is marked {@code "?"} rather than reporting text as a brightness
     *       reading.</li>
     *   <li>Fewer lines than nodes leaves trailing entries as {@code "-"} instead of shifting
     *       values into the wrong columns.</li>
     * </ul>
     */
    static String[] parseNodeValues(String output, List<String> nodes) {
        String[] out = new String[nodes.size()];
        for (int i = 0; i < out.length; i++) out[i] = "-";
        if (output == null) return out;

        int idx = 0;
        for (String line : output.split("\n")) {
            if (idx >= out.length) break;
            int tab = line.indexOf('\t');
            if (tab < 0) continue;                       // not a "label<TAB>value" line
            String v = line.substring(tab + 1).trim();

            // A '/' in a brightness reading means a label leaked into the value, i.e. the
            // line-break guarantee was violated. Report it as unusable instead of as data.
            if (v.indexOf('/') >= 0) v = "?";

            out[idx] = v.length() == 0 ? "-" : v;
            idx++;
        }
        return out;
    }

    private static String firstLine(String s) {
        if (s == null || s.length() == 0) return "";
        int i = s.indexOf('\n');
        String l = i < 0 ? s : s.substring(0, i);
        return l.length() > 200 ? l.substring(0, 200) : l.trim();
    }
}
