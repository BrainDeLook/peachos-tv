package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asks apps to publish their home screen cards. Some (Prime Video) only create their channel when the
 * home screen sends them INITIALIZE_PROGRAMS, which the stock launcher does after each install or
 * update; with it disabled they publish nothing, and Backdrops has nothing to show for them. Sent once
 * per app and version, like the stock launcher.
 */
final class ChannelSetup {

    private static final String FILE = "channel_setup";
    private static final AtomicBoolean running = new AtomicBoolean();

    private ChannelSetup() {
    }

    /** Package and preference reads must not delay Home's first layout after boot. */
    static void start(Context context) {
        if (!running.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                run(app);
            } finally {
                running.set(false);
            }
        }, "channel-setup").start();
    }

    /** Sends the broadcast to each app that listens for it and hasn't had it since it was installed or updated. */
    private static void run(Context context) {
        PackageManager pm = context.getPackageManager();
        SharedPreferences sent = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = sent.edit();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo r : pm.queryBroadcastReceivers(new Intent(TvContract.ACTION_INITIALIZE_PROGRAMS), 0)) {
            String pkg = r.activityInfo.packageName;
            if (BackdropPolicy.isStaticYouTube(pkg) || !seen.add(pkg)) continue;
            long updated;
            try {
                updated = pm.getPackageInfo(pkg, 0).lastUpdateTime;
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            if (sent.getLong(pkg, 0) == updated) continue;
            // Explicit: Android 8+ doesn't deliver implicit broadcasts to receivers in manifests.
            context.sendBroadcast(new Intent(TvContract.ACTION_INITIALIZE_PROGRAMS).setPackage(pkg));
            edit.putLong(pkg, updated);
        }
        // Forget removed apps, so a reinstall gets it again.
        for (String pkg : sent.getAll().keySet()) {
            if (!seen.contains(pkg)) edit.remove(pkg);
        }
        edit.apply();
    }
}
