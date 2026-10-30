package com.gathin.tvhome;

import android.content.SharedPreferences;
import android.view.KeyEvent;

/** A user-taught remote key, shared by the foreground screen and the accessibility helper. */
final class SettingsKeyBinding {
    static final String CODE = "settings_button_keycode";
    static final String SCAN = "settings_button_scancode";
    // Both entry points run on the main thread, in the same process. The settings screen owns its lifetime.
    private static Learning learning;

    interface Learned { void accept(int code, int scan); }

    static void begin(Learned callback) { learning = new Learning(callback); }
    static void cancel() {
        if (learning == null) return;
        // Finish consuming an already captured down/up pair even if the dialog closes mid-hold.
        if (learning.downTime < 0) learning = null;
        else learning.callback = null;
    }

    static boolean capture(KeyEvent event) {
        Learning current = learning;
        if (current == null) return false;
        if (current.downTime < 0) {
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0
                    || !canLearn(event.getKeyCode(), event.getScanCode())) return false;
            current.downTime = event.getDownTime();
            current.code = event.getKeyCode();
            current.scan = event.getScanCode();
        }
        if (event.getDownTime() != current.downTime || event.getKeyCode() != current.code
                || event.getScanCode() != current.scan) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (event.isCanceled()) {
                if (current.callback == null) learning = null;
                else current.downTime = -1;
            }
            else {
                learning = null;
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
        return code > 0 ? code == eventCode : code == KeyEvent.KEYCODE_UNKNOWN
                && eventCode == KeyEvent.KEYCODE_UNKNOWN && scan > 0 && scan == eventScan;
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

    private static final class Learning {
        Learned callback;
        long downTime = -1;
        int code, scan;
        Learning(Learned callback) { this.callback = callback; }
    }
}
