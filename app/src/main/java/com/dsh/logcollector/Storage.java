package com.dsh.logcollector;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the on-device output layout and hides the fact that writing {@code /sdcard/log} needs
 * different tricks on different builds.
 *
 * <p>Layout:
 * <pre>
 *   /sdcard/log/
 *   ├── 20260101-120000/          one directory per collection run
 *   │   ├── index.txt             what ran, in what mode, and what came out
 *   │   ├── capability.txt        device + privilege report
 *   │   ├── dmesg.txt
 *   │   ├── logcat_all.txt
 *   │   ├── dumpsys/…             one file per service
 *   │   ├── display/…             panel + backlight snapshot
 *   │   ├── kernel/…              /proc + /sys snapshot
 *   │   ├── media/…               screenshots / screen recording
 *   │   └── bugreport/…           full bugreport (opt-in, slow)
 *   ├── latest                    pointer to the newest run
 *   └── crash/                    automatic captures from the crash/ANR watcher
 * </pre>
 *
 * <p>Write tiers, chosen automatically by {@link #probeWrite}:
 * <ol>
 *   <li>{@link #TIER_DIRECT} - plain {@code File} IO works (legacy storage or all-files access).</li>
 *   <li>{@link #TIER_ROOT_COPY} - content is staged in the app dir then {@code su cp}ed into place.</li>
 *   <li>{@link #TIER_SHELL} - content is piped straight into the destination through a shell.</li>
 *   <li>{@link #TIER_APP_PRIVATE} - nothing else worked; everything lands in the app's own
 *       external directory and the UI tells the user how to unlock the real path.</li>
 * </ol>
 */
public final class Storage {

    public static final int TIER_DIRECT      = 0;
    public static final int TIER_ROOT_COPY   = 1;
    public static final int TIER_SHELL       = 2;
    public static final int TIER_APP_PRIVATE = 3;

    private Storage() { }

    // ------------------------------------------------------------------ probe

    public static final class Probe {
        public boolean direct;
        public int tier;
        public String detail = "";
        public String resolvedBase = "";
    }

    /** Figures out the best way to write into {@code targetDir}. Cheap; safe to call often. */
    public static Probe probeWrite(Context ctx, String targetDir) {
        Probe p = new Probe();
        File dir = new File(targetDir);

        // --- tier 0: can we just write there? -------------------------------
        try {
            if (!dir.exists()) {
                boolean made = dir.mkdirs();
                if (!made && !dir.exists()) throw new IllegalStateException("mkdirs failed");
            }
            File probe = new File(dir, ".write_probe_" + android.os.Process.myPid());
            boolean ok = probe.createNewFile() || probe.exists();
            if (ok) {
                Util.writeFile(probe, "probe");
                ok = probe.length() > 0;
                //noinspection ResultOfMethodCallIgnored
                probe.delete();
            }
            if (ok) {
                p.direct = true;
                p.tier = TIER_DIRECT;
                p.resolvedBase = targetDir;
                p.detail = "app uid can write " + targetDir + " directly";
                return p;
            }
        } catch (Throwable t) {
            // fall through to the higher tiers
        }

        // --- tier 1: root can copy for us -----------------------------------
        if (Shell.hasRoot()) {
            Shell.Result r = Shell.su("mkdir -p '" + esc(targetDir) + "' && touch '" + esc(targetDir)
                    + "/.probe' && rm -f '" + esc(targetDir) + "/.probe' && echo PROBE_OK", 15000);
            if (r.out.contains("PROBE_OK")) {
                p.direct = false;
                p.tier = TIER_ROOT_COPY;
                p.resolvedBase = targetDir;
                p.detail = "staged locally, copied into " + targetDir + " with su";
                return p;
            }
            p.detail = "su present but could not create " + targetDir + " (" + firstLine(r.err) + ")";
        }

        // --- tier 2: shell redirect ----------------------------------------
        boolean shellAllowed = true;
        try {
            shellAllowed = Prefs.get(ctx).shellFallback();
        } catch (Throwable ignored) { }

        if (shellAllowed) {
            Shell.Result r2 = Shell.app("mkdir -p '" + esc(targetDir) + "' 2>&1 && echo SHELL_OK", 12000);
            if (r2.out.contains("SHELL_OK")) {
                p.direct = false;
                p.tier = TIER_SHELL;
                p.resolvedBase = targetDir;
                p.detail = "written through a shell redirect into " + targetDir;
                return p;
            }
        }

        // --- tier 3: give up on the requested path --------------------------
        File priv = appPrivateDir(ctx);
        if (priv != null) {
            //noinspection ResultOfMethodCallIgnored
            priv.mkdirs();
        }
        p.direct = false;
        p.tier = TIER_APP_PRIVATE;
        p.resolvedBase = priv == null ? targetDir : priv.getAbsolutePath();
        p.detail = "cannot write " + targetDir
                + (shellAllowed ? "" : " (shell fallback is disabled in settings)")
                + " - falling back to " + p.resolvedBase
                + " (grant 'All files access' or run as root to use the real path)";
        return p;
    }

    public static File appPrivateDir(Context ctx) {
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) return new File(ext, "log");
        } catch (Throwable ignored) { }
        try {
            return new File(ctx.getFilesDir(), "log");
        } catch (Throwable t) {
            return null;
        }
    }

    // --------------------------------------------------------------- session

    /** One collection run: a timestamped directory plus the tier used to fill it. */
    public static final class Session {
        public final String requestedBase;
        public final String base;
        public final File   dir;
        public final int    tier;
        public final String tierDetail;
        public final String stamp;

        Session(String requestedBase, String base, File dir, int tier, String tierDetail, String stamp) {
            this.requestedBase = requestedBase;
            this.base = base;
            this.dir = dir;
            this.tier = tier;
            this.tierDetail = tierDetail;
            this.stamp = stamp;
        }

        /** Relative path of a file inside this run, e.g. {@code dumpsys/activity.txt}. */
        public String rel(String path) { return path; }

        public String absPath(String rel) {
            return new File(dir, rel).getAbsolutePath();
        }
    }

    /**
     * Creates a fresh run directory. Nothing is written outside it, so a failed run never
     * disturbs older ones.
     */
    public static Session begin(Context ctx, String requestedBase, String namePrefix) {
        Probe p = probeWrite(ctx, requestedBase);
        String stamp = Util.stamp();
        String leaf = (namePrefix == null || namePrefix.length() == 0 ? "" : namePrefix + "-") + stamp;

        File dir;
        if (p.tier == TIER_APP_PRIVATE) {
            dir = new File(p.resolvedBase, leaf);
        } else {
            dir = new File(requestedBase, leaf);
        }
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        if (p.tier == TIER_ROOT_COPY) {
            Shell.su("mkdir -p '" + esc(dir.getAbsolutePath()) + "' && chmod 777 '"
                    + esc(dir.getAbsolutePath()) + "'", 15000);
        }
        return new Session(requestedBase, p.resolvedBase, dir, p.tier, p.detail, stamp);
    }

    // ------------------------------------------------------------------ write

    /** Result of a write attempt, for the run index. */
    public static final class WriteResult {
        public final boolean ok;
        public final long bytes;
        public final String detail;
        WriteResult(boolean ok, long bytes, String detail) {
            this.ok = ok; this.bytes = bytes; this.detail = detail;
        }
    }

    /** Writes text to {@code session.dir/rel}, using whatever tier the session needs. */
    public static WriteResult writeText(Context ctx, Session s, String rel, String text) {
        if (text == null) text = "";
        byte[] data;
        try { data = text.getBytes("UTF-8"); } catch (Throwable t) { data = text.getBytes(); }
        return writeBytes(ctx, s, rel, data);
    }

    public static WriteResult writeBytes(Context ctx, Session s, String rel, byte[] data) {
        File dst = new File(s.dir, rel);

        // tier 0 - straight to disk
        if (s.tier == TIER_DIRECT || s.tier == TIER_APP_PRIVATE) {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            if (Util.writeBytes(dst, data)) {
                return new WriteResult(true, data.length, "direct");
            }
            // fall through and try harder
        }

        // tier 1/2 - stage inside the app dir, then move it with su
        File staging = stageFile(ctx, rel);
        if (staging == null) {
            return new WriteResult(false, 0, "no staging directory available");
        }
        if (!Util.writeBytes(staging, data)) {
            return new WriteResult(false, 0, "could not write staging file " + staging);
        }

        if (Shell.hasRoot()) {
            File parent = dst.getParentFile();
            String mk = parent == null ? "" : "mkdir -p '" + esc(parent.getAbsolutePath()) + "' && ";
            Shell.Result r = Shell.su(mk + "cp '" + esc(staging.getAbsolutePath()) + "' '"
                    + esc(dst.getAbsolutePath()) + "' && chmod 666 '" + esc(dst.getAbsolutePath())
                    + "' && echo COPIED", 60000);
            if (r.out.contains("COPIED")) {
                //noinspection ResultOfMethodCallIgnored
                staging.delete();
                return new WriteResult(true, data.length, "su copy");
            }
            return new WriteResult(false, data.length, "su copy failed: " + firstLine(r.err));
        }

        // no root - pipe the content into the destination
        Shell.Result r = Shell.pipe("mkdir -p '" + esc(dst.getParent()) + "' && cat > '"
                + esc(dst.getAbsolutePath()) + "'", new String(data, java.nio.charset.Charset.forName("UTF-8")), 60000);
        if (r.code == 0) {
            //noinspection ResultOfMethodCallIgnored
            staging.delete();
            return new WriteResult(true, data.length, "shell redirect");
        }
        return new WriteResult(false, 0, "write denied: " + firstLine(r.err));
    }

    /** Copies an existing local file into the run directory. Efficient path for big outputs. */
    public static WriteResult copyInto(Context ctx, Session s, String rel, File localFile) {
        if (localFile == null || !localFile.exists()) {
            return new WriteResult(false, 0, "source missing");
        }
        File dst = new File(s.dir, rel);
        long len = localFile.length();

        if (s.tier == TIER_DIRECT || s.tier == TIER_APP_PRIVATE) {
            if (Util.copyFile(localFile, dst)) return new WriteResult(true, len, "direct copy");
        }
        if (Shell.hasRoot()) {
            File parent = dst.getParentFile();
            String mk = parent == null ? "" : "mkdir -p '" + esc(parent.getAbsolutePath()) + "' && ";
            Shell.Result r = Shell.su(mk + "cp '" + esc(localFile.getAbsolutePath()) + "' '"
                    + esc(dst.getAbsolutePath()) + "' && chmod 666 '" + esc(dst.getAbsolutePath())
                    + "' && echo COPIED", 120000);
            if (r.out.contains("COPIED")) return new WriteResult(true, len, "su copy");
            return new WriteResult(false, 0, "su copy failed: " + firstLine(r.err));
        }
        return new WriteResult(false, 0, "no way to place " + dst.getAbsolutePath());
    }

    /**
     * Returns a File the app can definitely create, mirroring {@code rel}. Used as a staging
     * area and as the final destination in {@link #TIER_APP_PRIVATE} mode.
     */
    public static File stageFile(Context ctx, String rel) {
        File stagingRoot = new File(ctx.getCacheDir(), "stage");
        //noinspection ResultOfMethodCallIgnored
        stagingRoot.mkdirs();
        File f = new File(stagingRoot, rel);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        return f;
    }

    /** Creates a subdirectory inside the run, honouring the tier. */
    public static boolean mkdirs(Context ctx, Session s, String rel) {
        File d = new File(s.dir, rel);
        if (s.tier == TIER_DIRECT || s.tier == TIER_APP_PRIVATE) {
            if (d.exists() || d.mkdirs()) return true;
        }
        if (Shell.hasRoot()) {
            Shell.Result r = Shell.su("mkdir -p '" + esc(d.getAbsolutePath()) + "' && chmod 777 '"
                    + esc(d.getAbsolutePath()) + "' && echo MKDIR_OK", 15000);
            return r.out.contains("MKDIR_OK");
        }
        return d.exists();
    }

    // ----------------------------------------------------------------- latest

    /**
     * Points {@code <base>/latest} at the newest run. A real symlink is used when possible
     * (root, or a filesystem that supports it); otherwise a small pointer file is written,
     * because /sdcard on modern Android is FUSE-backed and rejects symlinks.
     */
    public static String updateLatest(Context ctx, Session s) {
        final String base = s.requestedBase;
        final String target = s.dir.getAbsolutePath();
        final String latestPath = base + "/latest";
        final String runName = s.dir.getName();

        // A symlink is the nicest result, so try it first when we have the power to make one.
        if (Shell.hasRoot()) {
            Shell.Result r = Shell.su("rm -rf '" + esc(latestPath) + "' && ln -s '" + esc(target)
                    + "' '" + esc(latestPath) + "' && echo LINKED", 15000);
            if (r.out.contains("LINKED")) return "symlink " + latestPath + " -> " + runName;
        }
        try {
            File lp = new File(latestPath);
            if (lp.exists()) Util.deleteTree(lp);
            if (lp.getParentFile() != null && !lp.getParentFile().exists()) {
                //noinspection ResultOfMethodCallIgnored
                lp.getParentFile().mkdirs();
            }
            // android.system.Os.symlink exists since API 21, so this needs no version guard and
            // avoids referencing java.nio.file (API 26+), whose class resolution can fail early
            // on older ART even when the call site is guarded.
            android.system.Os.symlink(target, latestPath);
            return "symlink " + latestPath + " -> " + runName;
        } catch (Throwable ignored) {
            // /sdcard is FUSE-backed on modern Android and rejects symlinks: fall through.
        }

        // Pointer directory fallback: latest/ contains a file naming the real run.
        StringBuilder sb = new StringBuilder();
        sb.append("The newest collection run is:\n\n  ").append(target).append("\n\n");
        sb.append("This device/filesystem does not allow a symlink named 'latest',\n");
        sb.append("so this directory only holds the pointer. Use the path above,\n");
        sb.append("or read WHERE.txt from a script.\n");
        Session pointer = new Session(base, s.base, new File(latestPath), s.tier, s.tierDetail, s.stamp);
        //noinspection ResultOfMethodCallIgnored
        pointer.dir.mkdirs();
        WriteResult w = writeText(ctx, pointer, "WHERE.txt", sb.toString());
        if (w.ok) return "pointer directory " + latestPath + "/WHERE.txt -> " + runName;

        // Last resort: a plain file next to the run directories.
        WriteResult w2 = writeText(ctx, pointer, "../latest.txt",
                target + "\n");
        return w2.ok ? ("pointer file " + base + "/latest.txt -> " + runName)
                     : ("could not create a 'latest' pointer (" + w2.detail + ")");
    }

    // ------------------------------------------------------------- retention

    /** Deletes the oldest run directories, keeping {@code keep} of them plus {@code latest}. */
    public static int prune(Context ctx, String base, int keep, String... extraProtected) {
        File dir = new File(base);
        if (!dir.isDirectory()) return 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        List<File> runs = new ArrayList<File>();
        for (File k : kids) {
            if (!k.isDirectory()) continue;
            String n = k.getName();
            if ("latest".equals(n)) continue;
            boolean prot = false;
            if (extraProtected != null) {
                for (String e : extraProtected) if (n.equals(e)) prot = true;
            }
            if (!prot) runs.add(k);
        }
        if (runs.size() <= keep) return 0;
        java.util.Collections.sort(runs, new java.util.Comparator<File>() {
            public int compare(File a, File b) {
                long x = a.lastModified(), y = b.lastModified();
                return x < y ? 1 : (x > y ? -1 : 0);
            }
        });
        int removed = 0;
        for (int i = keep; i < runs.size(); i++) {
            File f = runs.get(i);
            if (Shell.hasRoot() && !f.canWrite()) {
                Shell.su("rm -rf '" + esc(f.getAbsolutePath()) + "'", 60000);
            } else {
                // In staged tiers the directory may be root-owned; try both.
                Util.deleteTree(f);
                if (f.exists()) {
                    Shell.su("rm -rf '" + esc(f.getAbsolutePath()) + "'", 60000);
                }
            }
            if (!f.exists()) removed++;
        }
        return removed;
    }

    // ------------------------------------------------------------------ paths

    public static String defaultBase() { return "/sdcard/log"; }

    public static boolean externalStorageMounted() {
        try {
            return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Single-quote escape for shell interpolation. */
    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("'", "'\\''");
    }

    private static String firstLine(String s) {
        if (s == null || s.length() == 0) return "no error text";
        int i = s.indexOf('\n');
        String l = i < 0 ? s : s.substring(0, i);
        return l.length() > 200 ? l.substring(0, 200) : l.trim();
    }
}
