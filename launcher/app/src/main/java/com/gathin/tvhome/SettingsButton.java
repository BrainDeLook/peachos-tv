package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

/** Optional remote Settings hold. A tap is consumed without opening Android settings. */
final class SettingsButton {
    static final String KEY = "settings_hold_control_center";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Gesture gesture = new Gesture();
    private final Runnable open;
    private final Runnable held;

    SettingsButton(Runnable open) {
        this.open = open;
        held = () -> { if (gesture.held()) this.open.run(); };
    }

    void handle(KeyEvent event) {
        if (event.isCanceled()) { cancel(); return; }
        long timeout = ViewConfiguration.getLongPressTimeout();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (gesture.down(event.getDownTime())) {
                handler.removeCallbacks(held);
                handler.postDelayed(held, Math.max(0, timeout - (event.getEventTime() - event.getDownTime())));
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(held);
            // If the UI thread was busy at the timeout, release still honours the hold exactly once.
            if (gesture.up(event.getDownTime(), event.getEventTime(), timeout)) open.run();
        }
    }

    void cancel() { handler.removeCallbacks(held); gesture.cancel(); }

    static final class Gesture {
        private long downTime = -1;
        private boolean fired;
        boolean down(long time) {
            if (downTime == time) return false;
            downTime = time; fired = false; return true;
        }
        boolean held() {
            if (downTime < 0 || fired) return false;
            fired = true; return true;
        }
        boolean up(long time, long now, long timeout) {
            boolean open = downTime == time && !fired && now - time >= timeout;
            cancel(); return open;
        }
        void cancel() { downTime = -1; fired = false; }
    }
}
