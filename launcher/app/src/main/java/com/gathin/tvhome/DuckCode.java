package com.gathin.tvhome;

import android.view.KeyEvent;

/** State is confined to the foreground Home Activity. */
final class DuckCode {
    private static final int[] KEYS = {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_CENTER
    };
    private static final long GAP_MS = 4_000;
    private int matched;
    private long last;

    boolean enter(int key, long now) {
        if (matched > 0 && now - last > GAP_MS) matched = 0;
        int expected = KEYS[matched];
        if (key == expected || expected == KeyEvent.KEYCODE_DPAD_CENTER
                && (key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            matched++;
            last = now;
            if (matched == KEYS.length) { reset(); return true; }
        } else {
            matched = key == KEYS[0] ? 1 : 0;
            last = now;
        }
        return false;
    }

    void reset() { matched = 0; last = 0; }
}
