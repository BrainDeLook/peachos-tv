package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One shared external read for Home and its remote helper; subscriptions belong to their lifecycles. */
final class ConfigRefresh {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(0, 1, 2, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1), task -> new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "PeachThemeRefresh"));
    private static final List<Request> waiting = new ArrayList<>();
    private static Future<?> work;
    private static int generation;
    private static Config latest;
    private static long completedAt;

    static final class Request {
        private Consumer<Config> callback;
        Request(Consumer<Config> callback) { this.callback = callback; }
        boolean active() { return callback != null; }
        void cancel() {
            callback = null; waiting.remove(this);
            if (waiting.isEmpty() && work != null) {
                generation++; work.cancel(true); work = null; WORK.purge();
            }
        }
    }

    /** Called on the main thread. since lets late startup subscribers reuse that same completed refresh. */
    static Request request(Context context, long since, Consumer<Config> callback) {
        Request request = new Request(callback);
        if (latest != null && completedAt >= since && ThemeSnapshot.isCurrent(latest.themeSnapshot)) {
            Config result = latest;
            MAIN.post(() -> {
                if (!request.active()) return;
                Consumer<Config> ready = request.callback; request.callback = null; ready.accept(result);
            });
            return request;
        }
        waiting.add(request);
        if (work != null) return request;
        Context application = context.getApplicationContext();
        int ticket = ++generation;
        WORK.purge();
        work = WORK.submit(() -> {
            Config result;
            try { result = Config.load(application); }
            catch (RuntimeException unavailable) {
                Log.w("PeachStartup", "Background theme verification failed", unavailable);
                MAIN.post(() -> finish(ticket, null));
                return;
            }
            MAIN.post(() -> finish(ticket, result));
        });
        return request;
    }

    private static void finish(int ticket, Config result) {
        if (ticket != generation) return;
        work = null;
        if (result != null) { latest = result; completedAt = SystemClock.uptimeMillis(); }
        List<Request> ready = new ArrayList<>(waiting); waiting.clear();
        for (Request request : ready) {
            Consumer<Config> callback = request.callback; request.callback = null;
            if (callback != null && result != null) callback.accept(result);
        }
    }
}
