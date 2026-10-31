package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

/**
 * consume_volume_keys: some firmware (TCL G03, Android 11, #1100) applies a volume key before the key
 * services' filter and again when the service hands it back, so one tap moved two steps. The services swallow
 * Volume Up/Down instead, which leaves the firmware's first step; a swallowed key gets no auto-repeat, so a held
 * one is repeated here, from the same delay and at about the rate Android uses.
 *
 * The state is per process, not per service: with HomeKeyService and InputKeyService both bound, each sees the
 * same press. Repeated DOWNs, including remotes that leave repeatCount at zero, must not restart
 * the delay. TV firmware can consume volume keys in KeyboardInterceptor before onKeyEvent;
 * this helper cannot repair repetition on that path and must not be enabled globally.
 */
final class VolumeKeys {
    static final long REPEAT_DELAY_MS = 500;
    static final long REPEAT_INTERVAL_MS = 60;
    private static final long MAX_HOLD_MS = 15000;

    /** Created on first use, so the class loads in JVM tests that only call the pure methods. */
    private static Handler handler;
    private static Runnable step;
    private static final Press press = new Press();

    private VolumeKeys() {
    }

    /** AudioManager direction for a volume key, or 0 for any other key. */
    static int direction(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) return AudioManager.ADJUST_RAISE;
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return AudioManager.ADJUST_LOWER;
        return 0;
    }

    /** Swallows a volume key (true) and repeats it while held; any other key is left alone (false). */
    static boolean handle(Context context, KeyEvent event) {
        int direction = direction(event.getKeyCode());
        if (direction == 0) return false;
        int change = press.update(event.getAction(), event.getKeyCode(), event.getDeviceId(),
                event.getRepeatCount(), event.isCanceled());
        if (change == Press.NONE) return true;
        if (handler != null && step != null) handler.removeCallbacks(step);
        step = null;
        Log.d("PeachRemote", "Volume repeat " + (change == Press.START ? "started" : "stopped")
                + " key=" + event.getKeyCode() + " device=" + event.getDeviceId());
        if (change == Press.END) return true;
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) { press.clear(); return true; }
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        // A disconnected Bluetooth remote may never deliver UP. Bound the synthetic hold
        // so a lost event cannot leave a soundbar changing volume indefinitely.
        long repeatUntil = SystemClock.uptimeMillis() + MAX_HOLD_MS;
        step = new Runnable() {
            @Override
            public void run() {
                if (SystemClock.uptimeMillis() >= repeatUntil) { stop(); return; }
                audio.adjustVolume(direction, AudioManager.FLAG_SHOW_UI);
                handler.postDelayed(this, REPEAT_INTERVAL_MS);
            }
        };
        handler.postDelayed(step, REPEAT_DELAY_MS);
        return true;
    }

    /** Ends a repeat in progress, e.g. when the service goes away while a key is held. */
    static void stop() {
        if (handler != null && step != null) handler.removeCallbacks(step);
        step = null;
        press.clear();
    }

    /** One physical hold, shared by the two key services; no Android runtime needed to test it. */
    static final class Press {
        static final int NONE = 0, START = 1, END = 2;
        private int keyCode;
        private int deviceId;

        int update(int action, int key, int device, int repeatCount, boolean canceled) {
            boolean same = keyCode == key && deviceId == device;
            if (canceled || action == KeyEvent.ACTION_UP) {
                if (!same) return NONE;
                clear();
                return END;
            }
            if (action != KeyEvent.ACTION_DOWN || same || repeatCount != 0) return NONE;
            keyCode = key;
            deviceId = device;
            return START;
        }

        void clear() { keyCode = 0; deviceId = 0; }
    }
}
