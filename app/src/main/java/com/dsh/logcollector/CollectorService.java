package com.dsh.logcollector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that owns everything long-running:
 * <ul>
 *   <li>the rolling black-box log recorder (optionally)</li>
 *   <li>the crash / ANR watcher (optionally)</li>
 *   <li>the interval timer that triggers periodic collections</li>
 *   <li>one collection run at a time, so the UI can show progress and then leave</li>
 * </ul>
 */
public final class CollectorService extends Service {

    public static final String ACTION_START        = "com.dsh.logcollector.START";
    public static final String ACTION_STOP         = "com.dsh.logcollector.STOP";
    public static final String ACTION_COLLECT_NOW  = "com.dsh.logcollector.COLLECT_NOW";
    public static final String ACTION_RELOAD       = "com.dsh.logcollector.RELOAD";

    public static final String EXTRA_REASON   = "reason";
    public static final String EXTRA_AUTO     = "auto";
    public static final String EXTRA_BUFFER   = "buffer";

    public static final String CHANNEL_ID = "logcollector";
    public static final int    NOTIF_ID   = 0x4C43;   // "LC"

    // ------------------------------------------------------------ live state

    public interface Listener {
        void onStateChanged();
        void onProgress(String phase, String detail);
        void onRunFinished(Collector.Report report);
    }

    private static final List<Listener> sListeners = new ArrayList<Listener>();
    private static volatile CollectorService sInstance;

    private static volatile String sPhase = "";
    private static volatile String sDetail = "";
    private static volatile boolean sBusy;
    private static volatile long sLastAutoCollectMs;
    private static final AtomicBoolean sRuntimeBlackbox = new AtomicBoolean(false);
    private static final AtomicBoolean sRuntimeWatcher = new AtomicBoolean(false);
    private PowerManager.WakeLock sWake;
    private android.os.Handler sTimer;
    private Runnable sTimerTask;

    // ------------------------------------------------------------- accessors

    public static CollectorService instance() { return sInstance; }
    public static boolean isBusy() { return sBusy; }
    public static String phase() { return sPhase; }
    public static String detail() { return sDetail; }

    /** True when a foreground service carrying the mediaProjection type is actually in place. */
    public static boolean isProjectionServiceRunning() {
        return sInstance != null && (Build.VERSION.SDK_INT < 34 || sProjectionTypeActive);
    }

    public static void addListener(Listener l) {
        synchronized (sListeners) { if (!sListeners.contains(l)) sListeners.add(l); }
    }
    public static void removeListener(Listener l) {
        synchronized (sListeners) { sListeners.remove(l); }
    }

    private static void notifyState() {
        List<Listener> copy;
        synchronized (sListeners) { copy = new ArrayList<Listener>(sListeners); }
        for (Listener l : copy) { try { l.onStateChanged(); } catch (Throwable ignored) { } }
    }
    private static void notifyProgress(final String phase, final String detail) {
        sPhase = phase; sDetail = detail;
        List<Listener> copy;
        synchronized (sListeners) { copy = new ArrayList<Listener>(sListeners); }
        for (Listener l : copy) { try { l.onProgress(phase, detail); } catch (Throwable ignored) { } }
    }
    private static void notifyFinished(final Collector.Report r) {
        List<Listener> copy;
        synchronized (sListeners) { copy = new ArrayList<Listener>(sListeners); }
        for (Listener l : copy) { try { l.onRunFinished(r); } catch (Throwable ignored) { } }
    }

    // ---------------------------------------------------------------- intent

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, CollectorService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            try { ctx.startService(i); } catch (Throwable ignored) { }
        }
    }

    public static void stopService(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, CollectorService.class).setAction(ACTION_STOP));
        } catch (Throwable ignored) { }
    }

    public static void requestCollect(Context ctx, String reason) {
        Intent i = new Intent(ctx, CollectorService.class)
                .setAction(ACTION_COLLECT_NOW)
                .putExtra(EXTRA_REASON, reason);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            try { ctx.startService(i); } catch (Throwable ignored) { }
        }
    }

    public static void reload(Context ctx) {
        Intent i = new Intent(ctx, CollectorService.class).setAction(ACTION_RELOAD);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            try { ctx.startService(i); } catch (Throwable ignored) { }
        }
    }

    // -------------------------------------------------------------- lifecycle

    /** {@code ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC}; inlined so API &lt; 29 can link this. */
    private static final int FGS_DATA_SYNC = 0x1;
    /** {@code ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION}; inlined for the same reason. */
    private static final int FGS_MEDIA_PROJECTION = 0x20;

    private static volatile boolean sProjectionTypeActive;

    @Override public void onCreate() {
        super.onCreate();
        sInstance = this;
        createChannel();
        // Start with the dataSync type ONLY. The manifest also declares mediaProjection, and on
        // Android 14 a foreground service started with that type while no projection consent has
        // been granted throws SecurityException - which would kill this service on every launch.
        // The type is upgraded later, on demand, by ensureProjectionForeground().
        startForegroundTyped(false);
        sTimer = new android.os.Handler(android.os.Looper.getMainLooper());
    }

    /** (Re)starts this service in the foreground with exactly the type(s) we are entitled to. */
    private void startForegroundTyped(boolean withProjection) {
        Notification n = buildNotification("DSH LogCollector",
                withProjection ? "截屏授权已就绪" : "日志采集服务运行中", true);
        int type = FGS_DATA_SYNC | (withProjection ? FGS_MEDIA_PROJECTION : 0);

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIF_ID, n, type);
                sProjectionTypeActive = withProjection;
                return;
            } catch (Throwable t) {
                Log.warn("startForeground(type=" + type + ") refused: " + t);
            }
        }
        // Untyped call: uses the manifest types, which is fine on API < 34.
        try {
            startForeground(NOTIF_ID, n);
            sProjectionTypeActive = false;
        } catch (Throwable t) {
            Log.err("startForeground failed outright", t);
        }
    }

    /**
     * Promotes the running foreground service so it also carries the {@code mediaProjection} type.
     * Only meaningful on Android 14+, where {@link android.media.projection.MediaProjection}
     * refuses to work unless an FGS of that exact type is already running.
     *
     * @return true when a projection-type foreground service is (now) in place
     */
    static boolean ensureProjectionForeground() {
        CollectorService s = sInstance;
        if (s == null) return false;
        if (Build.VERSION.SDK_INT < 34) return true;   // the type is not enforced below 14
        if (sProjectionTypeActive) return true;
        s.startForegroundTyped(true);
        return sProjectionTypeActive;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (action == null) action = ACTION_START;

        switch (action) {
            case ACTION_STOP:
                shutdownEverything();
                stopForegroundSafe();
                stopSelf();
                return START_NOT_STICKY;

            case ACTION_RELOAD:
                reapplySettings();
                return START_STICKY;

            case ACTION_COLLECT_NOW: {
                String reason = intent.getStringExtra(EXTRA_REASON);
                boolean auto = intent.getBooleanExtra(EXTRA_AUTO, false);
                boolean buffer = intent.getBooleanExtra(EXTRA_BUFFER, false);
                startRun(reason == null ? "手动" : reason, auto, buffer);
                return START_STICKY;
            }

            case ACTION_START:
            default:
                reapplySettings();
                return START_STICKY;
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        shutdownEverything();
        sInstance = null;
        super.onDestroy();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Users expect the black box to survive swiping the app away.
        Prefs p = Prefs.get(this);
        if (p.blackboxOn() || p.timerOn() || p.autoCrash() || p.autoAnr()) {
            Intent restart = new Intent(this, CollectorService.class).setAction(ACTION_START);
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(restart);
                else startService(restart);
            } catch (Throwable ignored) { }
        }
        super.onTaskRemoved(rootIntent);
    }

    // -------------------------------------------------------------- settings

    private void reapplySettings() {
        Prefs p = Prefs.get(this);

        // ---------------------------------------------------- black box
        if (p.blackboxOn()) {
            if (!Blackbox.isRunning()) {
                String r = Blackbox.start(this, p.blackboxBufferKb(), p.blackboxMinutes());
                Log.line("blackbox start: " + r);
            }
            sRuntimeBlackbox.set(true);
        } else {
            if (Blackbox.isRunning()) {
                Blackbox.stop();
                Log.line("blackbox stopped");
            }
            sRuntimeBlackbox.set(false);
        }

        // ------------------------------------------------- crash watcher
        if (p.autoCrash() || p.autoAnr()) {
            if (!CrashWatcher.isRunning()) {
                CrashWatcher.start(getApplicationContext(), new CrashWatcher.Listener() {
                    public void onEvent(String kind, String detail) {
                        onCrashEvent(kind, detail);
                    }
                });
                Log.line("crash watcher started");
            }
            sRuntimeWatcher.set(true);
        } else {
            if (CrashWatcher.isRunning()) CrashWatcher.stop();
            sRuntimeWatcher.set(false);
        }

        // ------------------------------------------------------ timer
        if (p.timerOn()) scheduleTimer(p.timerMinutes()); else cancelTimer();

        updateNotificationIdle();
    }

    private void shutdownEverything() {
        cancelTimer();
        CrashWatcher.stop();
        Blackbox.stop();
        sRuntimeBlackbox.set(false);
        sRuntimeWatcher.set(false);
        sProjectionTypeActive = false;
        releaseWake();
    }

    private void scheduleTimer(int minutes) {
        cancelTimer();
        final long period = Math.max(1, minutes) * 60_000L;
        sTimerTask = new Runnable() {
            public void run() {
                Prefs p = Prefs.get(CollectorService.this);
                if (!p.timerOn()) return;
                if (!sBusy) {
                    startRun("定时", true, false);
                }
                if (sTimer != null && sTimerTask != null) sTimer.postDelayed(sTimerTask, period);
            }
        };
        sTimer.postDelayed(sTimerTask, period);
        Log.line("timer scheduled every " + minutes + " min");
    }

    private void cancelTimer() {
        if (sTimer != null && sTimerTask != null) {
            sTimer.removeCallbacks(sTimerTask);
            sTimerTask = null;
        }
    }

    // ------------------------------------------------------------------ runs

    private void onCrashEvent(String kind, String detail) {
        Prefs p = Prefs.get(this);
        if ("anr".equals(kind) && !p.autoAnr()) return;
        if (!"anr".equals(kind) && !p.autoCrash()) return;

        long now = System.currentTimeMillis();
        // Never stack automatic runs on top of each other.
        if (sBusy) { Log.line("auto capture skipped (" + kind + "): a run is already in progress"); return; }
        if (now - sLastAutoCollectMs < 30_000L) {
            Log.line("auto capture skipped (" + kind + "): debounced");
            return;
        }
        sLastAutoCollectMs = now;

        String label = "anr".equals(kind) ? "auto-anr" : "auto-crash";
        Log.line("auto capture triggered by " + kind + ": " + detail);
        startRun(label, true, true);
    }

    private void startRun(final String reason, final boolean auto, final boolean includeBlackbox) {
        if (sBusy) {
            notifyProgress("忙", "已有采集任务在运行，本次请求已忽略");
            return;
        }
        sBusy = true;
        notifyState();

        acquireWake();

        new Thread(new Runnable() {
            public void run() {
                Collector.Report rep = null;
                try {
                    updateNotification("采集中", reason + " · 正在收集…", true);
                    Prefs p = Prefs.get(getApplicationContext());

                    Collector.Options o = Collector.Options.from(getApplicationContext());
                    // Automatic captures are narrower by default so they finish fast.
                    if (auto && !"manual".equals(reason)) {
                        o.namePrefix = reason;
                        if (o.doBugreport) o.doBugreport = false;   // far too slow for a crash path
                        o.doRecord = false;                        // never record during a crash
                        o.budgetMs = 6 * 60 * 1000L;
                    }

                    rep = Collector.run(getApplicationContext(), o, new Collector.Progress() {
                        public void onStep(String phase, String detail) {
                            notifyProgress(phase, detail);
                            updateNotification("采集中", phase + " · " + detail, true);
                        }
                        public void onDone(Collector.Report r) { }
                    });

                    // ---- attach the black box window if we have one -----------
                    boolean wantBlackbox = includeBlackbox
                            && (Blackbox.isRunning()
                                || Blackbox.sizeOnDisk(getApplicationContext()) > 0);
                    if (wantBlackbox) {
                        try {
                            notifyProgress("黑匣子", "导出最近日志窗口…");
                            Blackbox.Extract ex = Blackbox.extract(getApplicationContext(), p.blackboxMinutes());
                            if (ex.text.length() > 0) {
                                Storage.Session s = new Storage.Session(
                                        rep.requestedDir, rep.dir, new File(rep.dir),
                                        rep.tier, rep.tierDetail, new File(rep.dir).getName());
                                Storage.WriteResult w = Storage.writeText(getApplicationContext(), s,
                                        "blackbox_window.txt", ex.text);
                                if (w.ok) {
                                    rep.warnings.add("已附加黑匣子窗口：" + ex.lines + " 行");
                                }
                            }
                        } catch (Throwable t) {
                            rep.warnings.add("黑匣子导出失败: " + t);
                        }
                    }

                    // ---- zip + upload ---------------------------------------
                    if (p.autoUpload() && p.uploadUrl().length() > 0) {
                        try {
                            notifyProgress("上传", "打包并上传…");
                            Storage.Session s = new Storage.Session(
                                    rep.requestedDir, rep.dir, new File(rep.dir),
                                    rep.tier, rep.tierDetail, new File(rep.dir).getName());
                            Packager.Result zr = Packager.zip(getApplicationContext(), s, null);
                            if (zr.ok) {
                                rep.zipPath = zr.path;
                                Packager.UploadResult ur = Packager.upload(
                                        getApplicationContext(), new File(zr.path),
                                        p.uploadUrl(), p.uploadToken());
                                rep.uploadResult = ur.ok ? ("上传成功: " + ur.detail)
                                                         : ("上传失败: " + ur.detail);
                                if (!ur.ok) rep.warnings.add(rep.uploadResult);
                            } else {
                                rep.warnings.add("打包失败: " + zr.detail);
                            }
                        } catch (Throwable t) {
                            rep.warnings.add("上传流程异常: " + t);
                        }
                    }

                    // ---- retention for timer-driven runs --------------------
                    if (auto && p.timerOn()) {
                        String base = rep.requestedDir;
                        int removed = Storage.prune(getApplicationContext(), base, p.timerKeep());
                        if (removed > 0) rep.warnings.add("定时保留策略清理了 " + removed + " 个旧目录");
                    }

                } catch (Throwable t) {
                    rep = new Collector.Report();
                    rep.ok = false;
                    rep.error = t.getClass().getSimpleName() + ": " + t.getMessage();
                    Log.line("collection run failed: " + t);
                } finally {
                    sBusy = false;
                    releaseWake();
                    updateNotificationIdle();
                    notifyState();
                    notifyFinished(rep);
                }
            }
        }, "collector-run").start();
    }

    // ---------------------------------------------------------------- notify

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "日志采集",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("采集进度与黑匣子录制状态");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) { }
    }

    private Notification buildNotification(String title, String text, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            //noinspection deprecation
            b = new Notification.Builder(this);
        }
        b.setContentTitle(title)
         .setContentText(text)
         .setSmallIcon(R.drawable.ic_stat)
         .setContentIntent(pi)
         .setOngoing(ongoing);

        if (Build.VERSION.SDK_INT >= 21) {
            // A "collect now" action straight from the notification shade.
            Intent collect = new Intent(this, CollectorService.class)
                    .setAction(ACTION_COLLECT_NOW)
                    .putExtra(EXTRA_REASON, "通知栏");
            int f = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pci = PendingIntent.getService(this, 1, collect, f);
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_save, "立即采集", pci).build());
        }
        if (Build.VERSION.SDK_INT < 16) {
            //noinspection deprecation
            return b.getNotification();
        }
        return b.build();
    }

    private void updateNotification(String title, String text, boolean ongoing) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(title, text, ongoing));
        } catch (Throwable ignored) { }
    }

    private void updateNotificationIdle() {
        Prefs p = Prefs.get(this);
        StringBuilder sb = new StringBuilder();
        if (Blackbox.isRunning()) sb.append("黑匣子录制中 · ");
        if (CrashWatcher.isRunning()) sb.append("崩溃监听中 · ");
        if (p.timerOn()) sb.append("每 ").append(p.timerMinutes()).append(" 分钟采集 · ");
        if (sb.length() == 0) sb.append("空闲");
        else if (sb.toString().endsWith(" · ")) sb.setLength(sb.length() - 3);
        updateNotification("DSH LogCollector", sb.toString(), true);
    }

    private void stopForegroundSafe() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else {
                //noinspection deprecation
                stopForeground(true);
            }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ wake

    private void acquireWake() {
        try {
            if (sWake != null && sWake.isHeld()) return;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            sWake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "logcollector:collect");
            sWake.setReferenceCounted(false);
            sWake.acquire(30 * 60 * 1000L);
        } catch (Throwable ignored) { }
    }

    private void releaseWake() {
        try {
            if (sWake != null && sWake.isHeld()) sWake.release();
        } catch (Throwable ignored) { }
        sWake = null;
    }
}
