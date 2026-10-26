package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

/** A preloaded, short UI tick. No media session, audio focus, or work on each animation frame. */
final class NavigationSound implements AutoCloseable {
    static final String KEY = "app_navigation_sound";
    private SoundPool pool;
    private int sample;
    private int stream;
    private boolean ready;
    private long lastPlayed;

    NavigationSound(Context context) {
        if (!context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY, true)) return;
        pool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((loadedPool, id, status) -> {
            if (pool == loadedPool && id == sample) ready = status == 0;
        });
        sample = pool.load(context, R.raw.navigation_tick, 1);
    }

    void play() {
        if (pool == null || !ready) return; // Never queue a delayed click after navigation.
        long now = SystemClock.uptimeMillis();
        if (now - lastPlayed < 45) return;
        lastPlayed = now;
        stream = pool.play(sample, .5f, .5f, 1, 0, 1f);
    }

    void stop() {
        if (pool != null && stream != 0) pool.stop(stream);
        stream = 0;
    }

    @Override public void close() {
        if (pool != null) {
            pool.release();
            pool = null;
        }
        ready = false;
    }
}
