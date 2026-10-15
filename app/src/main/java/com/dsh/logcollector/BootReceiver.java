package com.dsh.logcollector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the collector after a reboot or an app update, when the user has asked for automatic
 * collection.
 *
 * <p>On a locked boot ({@code BOOT_COMPLETED} delivered after the user unlocks) the external
 * storage path may not be readable yet, so the actual run is scheduled by the service rather than
 * executed inline here.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!boot && !updated) return;

        Prefs p = Prefs.get(context);
        Log.line("BootReceiver: " + action
                + " autoBoot=" + p.autoBoot()
                + " blackbox=" + p.blackboxOn()
                + " timer=" + p.timerOn());

        // Always bring the service up when anything long-running is enabled, since the black box
        // and the crash watcher both need it.
        boolean wantService = p.blackboxOn() || p.timerOn() || p.autoCrash() || p.autoAnr() || p.autoBoot();
        if (!wantService) return;

        try {
            CollectorService.start(context);
        } catch (Throwable t) {
            Log.err("BootReceiver: could not start the service", t);
        }

        if (p.autoBoot()) {
            // Delay a little: at BOOT_COMPLETED the storage layer and the log buffers are still
            // filling up, and a run started too early captures almost nothing useful.
            final Context app = context.getApplicationContext();
            new Thread(new Runnable() {
                public void run() {
                    try { Thread.sleep(45_000L); } catch (InterruptedException ignored) { }
                    try {
                        CollectorService.requestCollect(app, "boot");
                    } catch (Throwable t) {
                        Log.err("BootReceiver: could not request the boot run", t);
                    }
                }
            }, "boot-collect-delay").start();
        }
    }
}
