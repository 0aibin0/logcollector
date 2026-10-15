import java.lang.reflect.Method;

/**
 * Exercises the pure (Android-free) static helpers from the app's REAL compiled classes.
 *
 * The app's classes are compiled against android.jar, whose types are stubs that throw at
 * runtime - but the methods tested here never touch an Android API, so they can be invoked
 * directly on a desktop JVM. This verifies the shipped bytecode, not a re-implementation.
 *
 *   java -cp "android.jar;classes" LogicTest
 */
public class LogicTest {

    static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        Class<?> blackbox = Class.forName("com.dsh.logcollector.Blackbox");
        Class<?> viewer   = Class.forName("com.dsh.logcollector.LogcatViewerActivity");
        Class<?> storage  = Class.forName("com.dsh.logcollector.Storage");
        Class<?> util     = Class.forName("com.dsh.logcollector.Util");

        Method parseThreadtime = blackbox.getDeclaredMethod("parseThreadtime", String.class);
        parseThreadtime.setAccessible(true);
        Method levelOf = viewer.getDeclaredMethod("levelOf", String.class);
        levelOf.setAccessible(true);
        Method esc = storage.getDeclaredMethod("esc", String.class);
        esc.setAccessible(true);
        Method slug = util.getDeclaredMethod("slug", String.class);
        slug.setAccessible(true);
        Method bytes = util.getDeclaredMethod("bytes", long.class);
        bytes.setAccessible(true);
        Method fmtDuration = blackbox.getDeclaredMethod("fmtDuration", long.class);
        fmtDuration.setAccessible(true);

        long now = System.currentTimeMillis();
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US);

        // ============================================================ parseThreadtime
        System.out.println("--- Blackbox.parseThreadtime ---");

        // A real threadtime line must parse to (approximately) now.
        String line = sdf.format(new java.util.Date(now)) + "  1234  5678 I Tag     : hello";
        long t = (Long) parseThreadtime.invoke(null, line);
        check("recent threadtime line parses", t > 0);
        check("parsed time is within 5s of now", Math.abs(t - now) < 5000);

        // Garbage / continuation lines must be rejected, not mis-parsed.
        check("null -> 0", ((Long) parseThreadtime.invoke(null, (Object) null)) == 0L);
        check("empty -> 0", ((Long) parseThreadtime.invoke(null, "")) == 0L);
        check("continuation line -> 0", ((Long) parseThreadtime.invoke(null, "    at com.foo.Bar(Bar.java:12)")) == 0L);
        check("header line -> 0", ((Long) parseThreadtime.invoke(null, "--------- beginning of main")) == 0L);
        check("short line -> 0", ((Long) parseThreadtime.invoke(null, "01-01 00:00")) == 0L);
        check("letters where digits belong -> 0", ((Long) parseThreadtime.invoke(null, "ab-cd ef:gh:ij.kl  1 2 I T : x")) == 0L);

        // Year-boundary handling: a December line seen in January must roll back a year.
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_YEAR, -40);
        String oldLine = sdf.format(cal.getTime()) + "  1  2 I Tag : x";
        long oldT = (Long) parseThreadtime.invoke(null, oldLine);
        check("40-day-old line is not in the future", oldT <= now + 1000);
        check("40-day-old line parses to non-zero", oldT > 0);

        // ============================================================ levelOf
        System.out.println("\n--- LogcatViewerActivity.levelOf ---");
        check("I level",  ((Character) levelOf.invoke(null, "01-01 12:00:00.000  1234  5678 I Tag     : msg")) == 'I');
        check("E level",  ((Character) levelOf.invoke(null, "01-01 12:00:00.000  1234  5678 E Tag     : msg")) == 'E');
        check("W level",  ((Character) levelOf.invoke(null, "01-01 12:00:00.000  1234  5678 W Tag     : msg")) == 'W');
        check("D level",  ((Character) levelOf.invoke(null, "01-01 12:00:00.000  1234  5678 D Tag     : msg")) == 'D');
        check("F level",  ((Character) levelOf.invoke(null, "01-01 12:00:00.000  1234  5678 F Tag     : msg")) == 'F');
        check("V default",((Character) levelOf.invoke(null, "01-01 12:00:00.000  1234  5678 V Tag     : msg")) == 'V');
        // Wide pid/tid columns must not shift the level detection.
        check("wide columns", ((Character) levelOf.invoke(null, "01-01 12:00:00.000  123456  789012 E Tag : msg")) == 'E');
        // A single-digit pid/tid pair is narrower than the fixed offset.
        check("narrow columns still found", ((Character) levelOf.invoke(null, "01-01 12:00:00.000  1  2 E T : m")) == 'E');
        check("garbage -> V", ((Character) levelOf.invoke(null, "not a log line at all")) == 'V');
        check("empty -> V", ((Character) levelOf.invoke(null, "")) == 'V');

        // ============================================================ esc  (shell quoting)
        System.out.println("\n--- Storage.esc  (command-injection safety) ---");
        check("plain path unchanged", "/sdcard/log".equals(esc.invoke(null, "/sdcard/log")));
        check("space kept",           "/sdcard/my log".equals(esc.invoke(null, "/sdcard/my log")));
        // The critical property: a single quote must become '\'' so it cannot break out of the
        // surrounding single-quoted string in an sh -c command.
        String quoted = (String) esc.invoke(null, "/sdcard/it's here");
        check("single quote escaped as '\\''", quoted.equals("/sdcard/it'\\''s here"));
        check("escape closes and reopens the quote", quoted.contains("'\\''"));
        String inject = (String) esc.invoke(null, "/tmp'; rm -rf /; echo '");
        // The invariant that actually matters: after removing every correctly-escaped quote group
        // ("'\''"), no bare single quote may remain - otherwise it could terminate the surrounding
        // quoted string and let the rest of the input run as a command.
        String residue = inject.replace("'\\''", "");
        check("no unescaped quote survives", residue.indexOf('\'') < 0);
        check("contains escaped quote groups", inject.contains("'\\''"));
        System.out.println("      input     : /tmp'; rm -rf /; echo '");
        System.out.println("      escaped   : " + inject);
        System.out.println("      bare '\"' left: " + (residue.indexOf('\'') >= 0 ? "YES (BAD)" : "none"));

        // Emit every nasty path for the real-shell round-trip that PowerShell performs next.
        String[] nasty = {
                "/sdcard/log",
                "/sdcard/my log",
                "/sdcard/it's here",
                "/tmp'; rm -rf /; echo '",
                "/tmp/$(id)",
                "/tmp/`id`",
                "/tmp/\"quoted\"",
                "/tmp/a\\b",
                "/tmp/*",
                "/tmp/; echo pwned #",
        };
        StringBuilder rt = new StringBuilder();
        for (String n : nasty) {
            String e = (String) esc.invoke(null, n);
            rt.append(n).append('\t').append(e).append('\n');
        }
        java.io.PrintWriter pw = new java.io.PrintWriter("roundtrip.tsv", "UTF-8");
        pw.print(rt);
        pw.close();
        System.out.println("\n  wrote roundtrip.tsv (" + nasty.length + " cases) for shell verification");

        // ============================================================ slug
        System.out.println("\n--- Util.slug ---");
        check("slashes become underscores", "sys_class_backlight".equals(slug.invoke(null, "sys/class/backlight")));
        check("spaces become underscores", "foo_bar".equals(slug.invoke(null, "foo bar")));
        check("dots preserved", "dumpsys.all".equals(slug.invoke(null, "dumpsys.all")));
        check("colons replaced", "a_b".equals(slug.invoke(null, "a:b")));
        check("null -> unknown", "unknown".equals(slug.invoke(null, (Object) null)));

        // ============================================================ bytes
        System.out.println("\n--- Util.bytes ---");
        check("0 B",   "0 B".equals(bytes.invoke(null, 0L)));
        check("1023 B", "1023 B".equals(bytes.invoke(null, 1023L)));
        check("1.0 KB", "1.0 KB".equals(bytes.invoke(null, 1024L)));
        check("1.0 MB", "1.0 MB".equals(bytes.invoke(null, 1024L * 1024)));
        check("1.00 GB", "1.00 GB".equals(bytes.invoke(null, 1024L * 1024 * 1024)));

        // ============================================================ fmtDuration
        System.out.println("\n--- Blackbox.fmtDuration ---");
        check("30s",  "30 秒".equals(fmtDuration.invoke(null, 30L)));
        check("90s",  "1 分 30 秒".equals(fmtDuration.invoke(null, 90L)));
        check("1h",   "1 时 0 分".equals(fmtDuration.invoke(null, 3600L)));

        // ============================================================ retention (data-loss risk)
        System.out.println("\n--- Storage.prune  (retention must never delete the newest) ---");
        Method dirSize = util.getDeclaredMethod("dirSize", java.io.File.class);
        dirSize.setAccessible(true);
        Method countFiles = util.getDeclaredMethod("countFiles", java.io.File.class);
        countFiles.setAccessible(true);
        Method deleteTree = util.getDeclaredMethod("deleteTree", java.io.File.class);
        deleteTree.setAccessible(true);
        Method readFile = util.getDeclaredMethod("readFile", java.io.File.class);
        readFile.setAccessible(true);

        // Storage.prune(Context, String, int, String...) is the ONLY retention path the app
        // actually calls. Context is only consulted when a directory cannot be deleted normally,
        // which never happens for the local test directories, so null is safe here.
        Class<?> storageCls = Class.forName("com.dsh.logcollector.Storage");
        Method prune = storageCls.getDeclaredMethod("prune", android.content.Context.class,
                String.class, int.class, String[].class);
        prune.setAccessible(true);
        final String[] NO_PROTECT = new String[0];

        java.io.File base = new java.io.File("prunetest");
        deleteTree.invoke(null, base);
        //noinspection ResultOfMethodCallIgnored
        base.mkdirs();

        // Ten run directories, oldest first, each with a file so the contents are verifiable.
        for (int i = 0; i < 10; i++) {
            java.io.File d = new java.io.File(base, "run-" + i);
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            try {
                java.io.PrintWriter p = new java.io.PrintWriter(new java.io.File(d, "index.txt"), "UTF-8");
                p.print("run " + i);
                p.close();
            } catch (Exception e) { }
            //noinspection ResultOfMethodCallIgnored
            d.setLastModified(System.currentTimeMillis() - ((10 - i) * 60000L));
        }
        // A "latest" entry whose mtime is the NEWEST of all - this is exactly what made a
        // naive implementation consume one of the retention slots.
        java.io.File latest = new java.io.File(base, "latest");
        //noinspection ResultOfMethodCallIgnored
        latest.mkdirs();
        try {
            java.io.PrintWriter p = new java.io.PrintWriter(new java.io.File(latest, "WHERE.txt"), "UTF-8");
            p.print("pointer");
            p.close();
        } catch (Exception e) { }
        //noinspection ResultOfMethodCallIgnored
        latest.setLastModified(System.currentTimeMillis());

        check("10 runs + latest present", base.listFiles().length == 11);

        int removed = (Integer) prune.invoke(null, null, base.getAbsolutePath(), 3, NO_PROTECT);
        int remaining = base.listFiles().length;
        check("reported 7 removals (was " + removed + ")", removed == 7);
        check("kept exactly 3 runs + latest (was " + remaining + ")", remaining == 4);
        check("'latest' was NOT pruned", new java.io.File(latest, "WHERE.txt").exists());
        check("NEWEST run survived", new java.io.File(base, "run-9/index.txt").exists());
        check("oldest run was deleted", !new java.io.File(base, "run-0").exists());

        String kept = (String) readFile.invoke(null, new java.io.File(base, "run-9/index.txt"));
        check("surviving run content intact", "run 9".equals(kept));

        // Keeping more than exist must be a no-op, not a wipe.
        int removed2 = (Integer) prune.invoke(null, null, base.getAbsolutePath(), 99, NO_PROTECT);
        check("keep > available removes nothing (was " + removed2 + ")", removed2 == 0);
        check("still 3 runs + latest after no-op", base.listFiles().length == 4);

        // keep = 0 is the degenerate case: every run goes, 'latest' still stays.
        int removed3 = (Integer) prune.invoke(null, null, base.getAbsolutePath(), 0, NO_PROTECT);
        check("keep=0 removes the 3 remaining runs (was " + removed3 + ")", removed3 == 3);
        check("keep=0 still spares 'latest'", new java.io.File(latest, "WHERE.txt").exists());

        // A protected name must survive even when it would otherwise be pruned.
        java.io.File prot = new java.io.File(base, "pinned-run");
        //noinspection ResultOfMethodCallIgnored
        prot.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        prot.setLastModified(System.currentTimeMillis() - 999999L);   // oldest
        prune.invoke(null, null, base.getAbsolutePath(), 0, new String[] { "pinned-run" });
        check("explicitly protected run survived", prot.exists());

        // prune on a nonexistent base must be harmless.
        int removed4 = (Integer) prune.invoke(null, null, "no-such-dir-xyz", 3, NO_PROTECT);
        check("prune on missing dir returns 0", removed4 == 0);

        // dirSize / countFiles used for the run summary.
        java.io.File runDir = new java.io.File(base, "sized");
        //noinspection ResultOfMethodCallIgnored
        runDir.mkdirs();
        try {
            java.io.PrintWriter p = new java.io.PrintWriter(new java.io.File(runDir, "a.txt"), "UTF-8");
            p.print("0123456789");
            p.close();
        } catch (Exception e) { }
        check("dirSize counts nested files", ((Long) dirSize.invoke(null, runDir)) == 10L);
        check("countFiles counts nested files", ((Integer) countFiles.invoke(null, runDir)) == 1);
        check("dirSize on missing dir is 0", ((Long) dirSize.invoke(null, new java.io.File("nope-xyz"))) == 0L);
        check("countFiles on missing dir is 0", ((Integer) countFiles.invoke(null, new java.io.File("nope-xyz"))) == 0);
        check("dirSize(null) is 0", ((Long) dirSize.invoke(null, (Object) null)) == 0L);

        deleteTree.invoke(null, base);
        check("deleteTree removed the tree", !base.exists());

        // ============================================================ summary
        System.out.println("\n============================================");
        System.out.println("PASS " + pass + "   FAIL " + fail);
        System.out.println("============================================");
        if (fail > 0) System.exit(1);
    }

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  [PASS] " + what); }
        else    { fail++; System.out.println("  [FAIL] " + what); }
    }
}
