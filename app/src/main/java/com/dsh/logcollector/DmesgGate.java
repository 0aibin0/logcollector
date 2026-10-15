package com.dsh.logcollector;

/**
 * Stops the app from repeatedly invoking {@code dmesg} once the kernel has made clear it will
 * never work.
 *
 * <p><b>Why this class exists.</b> When an app-domain process calls {@code dmesg}, the seccomp
 * filter kills it with SIGSYS. The kernel treats that as a fatal signal and writes a
 * <i>tombstone</i> into {@code /data/tombstones/} - the ring buffer Android uses to preserve
 * native crash evidence. That buffer holds only the most recent ~32 entries and overwrites the
 * oldest.
 *
 * <p>Measured on a moto g(100) (Android 12): one collection run issued 4-6 doomed {@code dmesg}
 * calls, so roughly five runs were enough to overwrite <b>every</b> genuine native crash record
 * on the device. A diagnostic tool that destroys the evidence it exists to collect is worse than
 * no tool at all.
 *
 * <p>The verdict is therefore cached in memory <i>and</i> persisted, and every later command that
 * would start with {@code dmesg} is short-circuited before a process is ever spawned. The check
 * is a string test, so it costs nothing.
 */
final class DmesgGate {

    private DmesgGate() { }

    /** Cached verdict for this process: null = not yet known. */
    private static volatile Boolean sBlocked;
    private static volatile String  sReason = "";

    /** How long a persisted "blocked" verdict is trusted before one re-probe is allowed. */
    private static final long RECHECK_INTERVAL_MS = 7L * 24 * 3600 * 1000;

    // ------------------------------------------------------------- verdict

    static boolean isBlocked() {
        Boolean b = sBlocked;
        if (b != null) return b;
        return false;   // unknown yet: allow exactly one probe to find out
    }

    static String reason() { return sReason; }

    /** Loads a previously persisted verdict, if it is still fresh. */
    static void loadCached(Prefs p) {
        try {
            if (p.dmesgBlocked() && p.dmesgBlockedAt() > 0
                    && System.currentTimeMillis() - p.dmesgBlockedAt() < RECHECK_INTERVAL_MS) {
                sBlocked = Boolean.TRUE;
                sReason = p.dmesgBlockedReason();
            }
        } catch (Throwable ignored) { }
    }

    static void markBlocked(Prefs p, String reason) {
        sBlocked = Boolean.TRUE;
        // Store the BARE reason. Callers pass a display string that already embeds a label, and
        // persisting that label means the next run reads it back and adds the label again -
        // which grew the reason without bound (observed: nine nested
        // "dmesg BLOCKED (cached verdict, not re-probed):" prefixes in one report).
        sReason = stripLabel(reason);
        try {
            p.dmesgBlocked(true, sReason, System.currentTimeMillis());
        } catch (Throwable ignored) { }
    }

    /**
     * Removes any leading status labels from a cached-verdict reason so it can be re-stored
     * idempotently. Handles arbitrarily many nested prefixes from older builds.
     *
     * <p><b>Ordering matters.</b> The prefixes are matched longest-first and the scan stops at the
     * first hit. A shorter prefix such as {@code "dmesg BLOCKED"} also matches the beginning of
     * the longer {@code "dmesg BLOCKED (cached verdict, not re-probed):"}, so trying the short one
     * afterwards would strip a label mid-way and leave debris like
     * {@code "(cached verdict, not re-probed): dmesg BLOCKED ..."}.
     */
    private static String stripLabel(String reason) {
        if (reason == null) return "";
        String s = reason.trim();
        // Longest first: once one matches, the remainder is re-examined from the top.
        String[] prefixes = {
                "dmesg BLOCKED (cached verdict, not re-probed):",
                "dmesg BLOCKED (cached verdict):",
                "dmesg BLOCKED -",
                "dmesg NOT AVAILABLE",
                "dmesg BLOCKED",
        };
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 100) {
            changed = false;
            for (String pfx : prefixes) {
                if (s.regionMatches(true, 0, pfx, 0, pfx.length())) {
                    // Require a boundary after the bare label so it cannot bite into a longer
                    // form that merely starts with the same words.
                    if ("dmesg BLOCKED".equals(pfx) && s.length() > pfx.length()) {
                        char next = s.charAt(pfx.length());
                        if (Character.isLetterOrDigit(next) || next == '(') continue;
                    }
                    s = s.substring(pfx.length()).trim();
                    changed = true;
                    break;
                }
            }
        }
        return s;
    }

    static void markReadable(Prefs p) {
        sBlocked = Boolean.FALSE;
        sReason = "";
        try {
            p.dmesgBlocked(false, "", System.currentTimeMillis());
        } catch (Throwable ignored) { }
    }

    /** Clears the in-memory verdict so one fresh probe may run (user-initiated only). */
    static void allowOneReprobe() {
        sBlocked = null;
        sReason = "";
    }

    /** True when a fresh probe is permitted (nothing cached, or the user asked for one). */
    static boolean mayProbe() {
        return sBlocked == null;
    }

    // -------------------------------------------------------- command match

    /**
     * True when running {@code cmd} would spawn the {@code dmesg} binary.
     *
     * <p>Splits on the shell operators that separate simple commands and checks whether any of the
     * resulting pieces begins with {@code dmesg}. Redirections that precede the command word are
     * skipped. This deliberately over-approximates: a false positive only costs us a skipped
     * command that could not have worked anyway, while a false negative costs a tombstone.
     */
    static boolean commandUsesDmesg(String cmd) {
        if (cmd == null) return false;
        String[] parts = cmd.split("[;|\\n]|&&|\\|\\|");
        for (String raw : parts) {
            if (raw == null) continue;
            String p = raw.trim();
            // Skip leading redirections such as "2>/dev/null" or ">/dev/null".
            while (p.length() > 0) {
                if (p.charAt(0) == '>') {
                    int sp = p.indexOf(' ');
                    if (sp < 0) return false;
                    p = p.substring(sp + 1).trim();
                } else if (p.length() > 1 && Character.isDigit(p.charAt(0)) && p.charAt(1) == '>') {
                    int sp = p.indexOf(' ');
                    if (sp < 0) return false;
                    p = p.substring(sp + 1).trim();
                } else {
                    break;
                }
            }
            if (p.startsWith("dmesg")) {
                // Must be the whole word: "dmesg", "dmesg -t", "dmesg>/dev/null" - but not
                // "dmesgfoo". The redirection character matters: Capabilities.probeDmesg()
                // issues exactly "dmesg >/dev/null 2>&1; echo ..." style commands.
                if (p.length() == 5) return true;
                char c = p.charAt(5);
                if (c == ' ' || c == '\t' || c == '>' || c == '<' || c == '|' || c == '&') {
                    return true;
                }
            }
        }
        return false;
    }

    /** The explanatory text substituted for a skipped dmesg command. */
    static String skipNotice() {
        return "dmesg SKIPPED: the kernel seccomp filter kills any dmesg call from this app with "
             + "SIGSYS, and each attempt writes a tombstone into /data/tombstones/, overwriting "
             + "real native-crash evidence. Not retrying.\n"
             + (sReason.length() > 0 ? ("earlier probe said: " + sReason + "\n") : "");
    }
}
