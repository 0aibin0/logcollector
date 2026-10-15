package com.dsh.logcollector;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;

/**
 * Minimal content provider so {@link Intent#ACTION_SEND} can hand out a readable
 * {@code content://} URI for a file that lives outside the app's own sandbox.
 *
 * <p>Writing a full {@code FileProvider} subclass is not possible here because the AndroidX
 * library is not available to this build (no Gradle, no network). This class therefore implements
 * the two methods the framework actually calls for a read-only share: {@code getType} and
 * {@code openFile}. It is deliberately not exported, and it refuses any path outside the
 * configured base directory.
 */
public final class SilentFileProvider extends android.content.ContentProvider {

    public SilentFileProvider() { }

    /** Builds a content URI for {@code file}, or falls back to a file URI on failure. */
    public static Uri getUriForFile(Context ctx, File file) {
        try {
            return Uri.parse("content://" + ctx.getPackageName() + ".share" + file.getAbsolutePath());
        } catch (Throwable t) {
            //noinspection deprecation
            return Uri.fromFile(file);
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        String p = uri.getPath();
        if (p == null) return "application/octet-stream";
        String l = p.toLowerCase();
        if (l.endsWith(".zip")) return "application/zip";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".mp4")) return "video/mp4";
        if (l.endsWith(".txt") || l.endsWith(".log")) return "text/plain";
        return "application/octet-stream";
    }

    @Override public android.database.Cursor query(Uri uri, String[] projection, String selection,
                                                   String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override public Uri insert(Uri uri, android.content.ContentValues values) { return null; }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override public int update(Uri uri, android.content.ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }

    @Override public android.os.ParcelFileDescriptor openFile(Uri uri, String mode)
            throws java.io.FileNotFoundException {
        if (mode != null && mode.contains("w")) {
            throw new java.io.FileNotFoundException("read-only provider");
        }
        String path = uri.getPath();
        if (path == null) throw new java.io.FileNotFoundException("no path in " + uri);
        File f = new File(path);
        if (!f.exists()) throw new java.io.FileNotFoundException(path + " does not exist");

        // Only ever serve files from the log tree or the app's own directories.
        String abs = f.getAbsolutePath();
        boolean allowed = abs.startsWith("/sdcard/") || abs.startsWith("/storage/")
                || abs.startsWith("/mnt/sdcard");
        if (!allowed && getContext() != null) {
            Context c = getContext();
            String dataDir = c.getApplicationInfo().dataDir;
            File ext = c.getExternalFilesDir(null);
            allowed = (dataDir != null && abs.startsWith(dataDir))
                    || (ext != null && abs.startsWith(ext.getAbsolutePath()));
        }
        if (!allowed) throw new java.io.FileNotFoundException("refusing to serve " + abs);

        return android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
