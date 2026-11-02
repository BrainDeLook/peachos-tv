package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/** Optional companion IPC. No protocol engine, decoding or polling loop runs in the launcher. */
final class AirPlayControl implements AutoCloseable {
    static final String PACKAGE = "com.gathin.peachos.airplay";
    static final Uri URI = Uri.parse("content://com.gathin.peachos.airplay.control");
    static final int OFF = 0, STARTING = 1, ON = 2, STOPPING = 3, ERROR = 4;
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(0, 1, 2, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), task -> new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "PeachAirPlay"));
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private IntConsumer listener;
    private int generation, state = STARTING;
    private volatile boolean closed;
    private boolean refreshing, refreshAgain, commandPending, stateDelivered;
    private Future<?> statusWork;
    private final Runnable timeout = this::refresh;
    private final ContentObserver changes = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { refresh(); }
    };

    static boolean available(Context context) {
        PackageManager pm = context.getPackageManager();
        return pm.resolveContentProvider(URI.getAuthority(), 0) != null
                && pm.checkSignatures(context.getPackageName(), PACKAGE) == PackageManager.SIGNATURE_MATCH;
    }

    AirPlayControl(Context host, IntConsumer listener) {
        context = host.getApplicationContext();
        this.listener = listener;
        context.getContentResolver().registerContentObserver(URI, false, changes);
        refresh();
    }

    void toggle() {
        if (closed || commandPending || state == STARTING || state == STOPPING) return;
        boolean enable = state != ON;
        commandPending = true;
        int ticket = ++generation;
        deliver(enable ? STARTING : STOPPING);
        IO.execute(() -> {
            int result = request(enable ? "enable" : "disable");
            main.post(() -> {
                commandPending = false;
                if (!closed && ticket == generation) { deliver(result); refresh(); }
            });
        });
    }

    private void refresh() {
        if (closed || commandPending) return;
        if (refreshing) { refreshAgain = true; return; }
        refreshing = true;
        int ticket = generation;
        statusWork = IO.submit(() -> {
            int result = closed ? OFF : request("status");
            if (closed) return;
            main.post(() -> {
                statusWork = null;
                refreshing = false;
                if (!closed && ticket == generation && !commandPending) deliver(result);
                if (refreshAgain) { refreshAgain = false; refresh(); }
            });
        });
    }

    private int request(String method) {
        // Validate the external reply once here, then pass only known states to the UI.
        try {
            if ("enable".equals(method)) {
                Status status = readStatus("status");
                if (status.state == ERROR) return ERROR;
                if (!status.configured) {
                    // Only the module's own visible button can accept permission and enable reception.
                    context.startActivity(new android.content.Intent().setComponent(
                            new android.content.ComponentName(PACKAGE, "com.codelphi.airplay.SetupActivity"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                    return OFF;
                }
                android.content.Intent service = new android.content.Intent().setComponent(
                        new android.content.ComponentName(PACKAGE, "com.codelphi.airplay.AirPlayService"));
                if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
                else context.startService(service);
                return STARTING;
            }
            return readStatus(method).state;
        } catch (RuntimeException unavailable) {
            android.util.Log.w("PeachAirPlay", "Companion " + method + " unavailable", unavailable);
            return ERROR;
        }
    }

    private record Status(int state, boolean configured) { }

    private Status readStatus(String method) {
        Bundle reply = context.getContentResolver().call(URI, method, null, null);
        if (reply == null) return new Status(ERROR, false);
        Object protocol = reply.get("protocol"), state = reply.get("state"), configured = reply.get("configured");
        if (!(protocol instanceof Integer version) || version != 1
                || !(state instanceof Integer value) || value < OFF || value > ERROR
                || !(configured instanceof Boolean allowed)) return new Status(ERROR, false);
        return new Status(value, allowed);
    }

    private void deliver(int value) {
        boolean changed = !stateDelivered || state != value;
        state = value;
        main.removeCallbacks(timeout);
        if (state == STARTING || state == STOPPING) main.postDelayed(timeout, 20000);
        if (!changed) return;
        stateDelivered = true;
        listener.accept(state);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        ++generation;
        // Obsolete status reads must not queue behind another opening's provider call. A read
        // already in Binder may finish, but accepted enable/disable commands still run normally.
        if (statusWork != null) {
            statusWork.cancel(false);
            IO.purge();
            statusWork = null;
        }
        context.getContentResolver().unregisterContentObserver(changes);
        main.removeCallbacksAndMessages(null);
        listener = null;
        // User commands already accepted by toggle still complete after the panel closes.
    }
}
