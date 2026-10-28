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
    private int openSample;
    private int returnSample;
    private int stream;
    private boolean ready;
    private boolean openReady;
    private boolean returnReady;
    private long lastPlayed;
    private long returnRequestedAt;

    NavigationSound(Context context) {
        if (!context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY, true)) return;
        pool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((loadedPool, id, status) -> {
            if (pool != loadedPool) return;
            if (id == sample) ready = status == 0;
            else if (id == openSample) openReady = status == 0;
            else if (id == returnSample) {
                returnReady = status == 0;
                // HOME can create another Activity in the same process. Let its freshly loaded
                // return cue play only during that first short resume, never as a late queued click.
                long requested = returnRequestedAt;
                returnRequestedAt = 0;
                if (returnReady && requested != 0 && SystemClock.uptimeMillis() - requested <= 180) {
                    playSample(returnSample);
                }
            }
        });
        sample = pool.load(context, R.raw.navigation_tick, 1);
        openSample = pool.load(context, R.raw.app_open, 1);
        returnSample = pool.load(context, R.raw.app_return, 1);
    }

    void play() {
        if (pool == null || !ready) return; // Never queue a delayed click after navigation.
        long now = SystemClock.uptimeMillis();
        if (now - lastPlayed < 45) return;
        lastPlayed = now;
        playSample(sample);
    }

    void playOpen() {
        returnRequestedAt = 0;
        if (pool != null && openReady) playSample(openSample);
    }

    void playReturn() {
        if (pool == null) return;
        if (returnReady) playSample(returnSample);
        else returnRequestedAt = SystemClock.uptimeMillis();
    }

    private void playSample(int id) {
        stop(); // A new action replaces the old tail, including rapid open/back/navigation.
        stream = pool.play(id, .5f, .5f, 1, 0, 1f);
    }

    void stop() {
        returnRequestedAt = 0;
        if (pool != null && stream != 0) pool.stop(stream);
        stream = 0;
    }

    @Override public void close() {
        if (pool != null) {
            pool.release();
            pool = null;
        }
        ready = false;
        openReady = false;
        returnReady = false;
    }
}
