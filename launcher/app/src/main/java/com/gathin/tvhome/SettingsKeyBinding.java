package com.gathin.tvhome;

import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** A user-taught remote key, shared by the foreground screen and the accessibility helper. */
final class SettingsKeyBinding {
    static final String CODE = "settings_button_keycode";
    static final String SCAN = "settings_button_scancode";
    // Both entry points run on the main thread, in the same process. The settings screen owns its lifetime.
    private static Learning learning;
    static final long FINISH_GESTURE_MS = 2000;
    private static Handler cleanupHandler;
    private static final Runnable cleanup = () -> {
        if (learning != null && learning.expired(SystemClock.uptimeMillis())) abort();
    };

    interface Learned { void accept(int code, int scan); }

    static boolean isLearning() { return learning != null; }
    static void begin(Learned callback) {
        abort();
        learning = new Learning(callback);
        HomeKeyService.refreshHomeSetup();
    }
    static void cancel() {
        if (learning == null) return;
        // Drain an already consumed DOWN briefly; a missing UP must not keep filtering forever.
        if (learning.downTime < 0) { abort(); return; }
        learning.cancel(SystemClock.uptimeMillis());
        if (cleanupHandler == null) cleanupHandler = new Handler(Looper.getMainLooper());
        cleanupHandler.removeCallbacks(cleanup);
        cleanupHandler.postDelayed(cleanup, FINISH_GESTURE_MS);
    }

    static void abort() {
        if (learning == null) return;
        learning = null;
        if (cleanupHandler != null) cleanupHandler.removeCallbacks(cleanup);
        HomeKeyService.refreshHomeSetup();
    }

    static boolean capture(KeyEvent event) {
        Learning current = learning;
        if (current == null) return false;
        if (current.expired(event.getEventTime())) { abort(); return false; }
        if (current.callback != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && current.downTime >= 0 && event.getDownTime() != current.downTime) current.downTime = -1;
        if (current.downTime < 0) {
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0
                    || !canLearn(event.getKeyCode(), event.getScanCode())) return false;
            current.downTime = event.getDownTime();
            current.code = event.getKeyCode();
            current.scan = event.getScanCode();
            current.device = event.getDeviceId();
        }
        if (event.getDownTime() != current.downTime || event.getKeyCode() != current.code
                || event.getScanCode() != current.scan || event.getDeviceId() != current.device) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (event.isCanceled()) {
                if (current.callback == null) {
                    abort();
                }
                else current.downTime = -1;
            }
            else {
                abort();
                if (current.callback != null) current.callback.accept(current.code, current.scan);
            }
        }
        return true;
    }

    static boolean matches(KeyEvent event, SharedPreferences choices) {
        if (!choices.getBoolean(SettingsButton.KEY, false)) return false;
        int code = choices.getInt(CODE, -1);
        int scan = choices.getInt(SCAN, 0);
        return matches(code, scan, event.getKeyCode(), event.getScanCode());
    }

    static boolean matches(int code, int scan, int eventCode, int eventScan) {
        return code > 0 ? code == eventCode && (!physicalScan(code, scan) || scan == eventScan) : code == KeyEvent.KEYCODE_UNKNOWN
                && eventCode == KeyEvent.KEYCODE_UNKNOWN && scan > 0 && scan == eventScan;
    }

    private static boolean physicalScan(int code, int scan) {
        // These semantic Settings buttons can change Linux scan across IR/Bluetooth transports.
        return scan > 0 && code != KeyEvent.KEYCODE_SETTINGS && code != KeyEvent.KEYCODE_NOTIFICATION
                && code != KeyEvent.KEYCODE_MENU;
    }

    static boolean canLearn(int code, int scan) {
        switch (code) {
            case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP: case KeyEvent.KEYCODE_WAKEUP: case KeyEvent.KEYCODE_ALL_APPS:
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return false;
            default: return code > 0 || scan > 0;
        }
    }

    static final class Learning {
        Learned callback;
        long downTime = -1;
        int code, scan, device;
        long finishBy = Long.MAX_VALUE;
        Learning(Learned callback) { this.callback = callback; }
        void cancel(long now) { callback = null; finishBy = now + FINISH_GESTURE_MS; }
        boolean expired(long now) { return callback == null && now >= finishBy; }
    }
}
