package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

/** Tiny pixel sprite. Only its own bounds are redrawn while Home is visible. */
final class DuckWalker extends View {
    private static final int WIDTH = 26;
    private static final String[] BODY = {
            "..........DDDDDD..........",
            ".........DYYYYYYD.........",
            ".........DYYWKYYD.........",
            ".........DYYYYYYDOOO......",
            ".........DDYYYYDDOOOO.....",
            "...DDDDDDYYYYDD...........",
            "..DYYYYYYYYYYYYYD.........",
            ".DYYYYYYYYYYYYYYYYD.......",
            ".DYYYYLLLYYYYYYYYYD.......",
            ".DYYYYYYYYYYYYYYYYD.......",
            "..DYYYYYYYYYYYYYYD.......",
            "...DDYYYYYYYYYYDD.........",
            ".....DDDDDDDDDD..........."
    };
    private static final String[] WINGS = {
            ".DYYYYLLLYYYYYYYYYD.......",
            ".DYYYYLLLYYYYYYYYYD.......",
            ".DYYYYLLLYYYYYYYYYD.......",
            ".DYYYYLLLYYYYYYYYYD......."
    };
    private static final String[] TAILS = {
            "...DDDDDDYYYYDD...........",
            "..DDDDDDDYYYYDD...........",
            "...DDDDDDYYYYDD...........",
            "....DDDDDYYYYDD..........."
    };
    private static final String[][] FEET = {
            {".......Y......Y...........", "......OOO.....OOO.........", ".....OOO.......OOO........"},
            {".......Y......Y...........", ".......OOO...OO..........", "........OOO...OOO........"},
            {".......Y......Y...........", ".......OOO....OOO.........", "........OOO..OOO........."},
            {".......Y......Y...........", "......OO....OOO..........", ".....OOO....OOO.........."}
    };
    private final Bitmap[] frames = new Bitmap[4];
    private final Bitmap[] quackFrames = new Bitmap[4];
    private final SoundPool sound;
    private int quackSample;
    private boolean quackReady;
    private long nextQuack;
    private long quackStarted;
    private final Paint paint = new Paint();
    private final Rect destination = new Rect();
    private final float speed;
    private final float density;
    private float baseline;
    private final float inset;
    private final int rightMargin;
    private long lastFrame;
    private float x;
    private int facing = 1;
    private boolean running;
    private final Runnable tick;

    DuckWalker(Context context, int rightMargin) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        speed = 38f * density;
        inset = 24f * density;
        x = inset;
        this.rightMargin = rightMargin;
        tick = new Runnable() {
            @Override public void run() {
                if (!running || getParent() == null) return;
                long now = SystemClock.uptimeMillis();
                float dt = Math.min(0.08f, (now - lastFrame) / 1000f);
                lastFrame = now;
                ViewGroup parent = (ViewGroup) getParent();
                float edge = Math.max(inset, parent.getWidth() - getWidth() - rightMargin);
                x += facing * speed * dt;
                if (x >= edge) { x = edge; facing = -1; }
                else if (x <= inset) { x = inset; facing = 1; }
                setTranslationX(x);
                int phase = (int) (now / 160) & 3;
                setY(baseline - ((phase & 1) == 0 ? 0 : 2 * density));
                if (now >= nextQuack) {
                    quackStarted = now;
                    nextQuack = now + 8_000;
                    if (quackReady) sound.play(quackSample, .36f, .36f, 1, 0, 1f);
                }
                invalidate();
                postDelayed(this, 42);
            }
        };
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        sound = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        sound.setOnLoadCompleteListener((pool, sample, status) -> {
            if (sample == quackSample) quackReady = status == 0;
        });
        quackSample = sound.load(context, R.raw.duck_quack, 1);
        for (int frame = 0; frame < frames.length; frame++) {
            int[] pixels = new int[WIDTH * 16];
            for (int row = 0; row < BODY.length + FEET[frame].length; row++) {
                String line = row == 5 ? TAILS[frame] : row == 8 ? WINGS[frame]
                        : row < BODY.length ? BODY[row] : FEET[frame][row - BODY.length];
                for (int col = 0; col < line.length(); col++) {
                    int color = switch (line.charAt(col)) {
                        case 'D' -> Color.rgb(110, 69, 45);
                        case 'Y' -> Color.rgb(255, 220, 119);
                        case 'L' -> Color.rgb(255, 242, 165);
                        case 'W' -> Color.WHITE;
                        case 'K' -> Color.rgb(42, 35, 32);
                        case 'O' -> Color.rgb(250, 132, 64);
                        default -> Color.TRANSPARENT;
                    };
                    pixels[row * WIDTH + col] = color;
                }
            }
            frames[frame] = Bitmap.createBitmap(pixels, WIDTH, 16, Bitmap.Config.ARGB_8888);
            int[] openBeak = pixels.clone();
            for (int col = 17; col <= 20; col++) openBeak[4 * WIDTH + col] = Color.TRANSPARENT;
            openBeak[5 * WIDTH + 15] = Color.rgb(110, 69, 45);
            for (int col = 16; col <= 19; col++) openBeak[5 * WIDTH + col] = Color.rgb(250, 132, 64);
            openBeak[2 * WIDTH + 12] = Color.rgb(42, 35, 32);
            openBeak[3 * WIDTH + 14] = Color.rgb(255, 150, 147);
            quackFrames[frame] = Bitmap.createBitmap(openBeak, WIDTH, 16, Bitmap.Config.ARGB_8888);
        }
    }

    void setBaseline(float y) { baseline = y; setY(y); }

    void start() {
        if (running) return;
        running = true;
        lastFrame = SystemClock.uptimeMillis();
        nextQuack = lastFrame + 1_600;
        post(tick);
    }

    void stop() {
        running = false;
        removeCallbacks(tick);
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        sound.release();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int frame = (int) (SystemClock.uptimeMillis() / 160) & 3;
        int save = canvas.save();
        if (facing < 0) { canvas.scale(-1, 1, getWidth() / 2f, getHeight() / 2f); }
        destination.set(0, 0, getWidth(), getHeight());
        long elapsed = SystemClock.uptimeMillis() - quackStarted;
        boolean mouthOpen = elapsed < 240 || elapsed >= 300 && elapsed < 540;
        canvas.drawBitmap(mouthOpen ? quackFrames[frame] : frames[frame], null, destination, paint);
        canvas.restoreToCount(save);
    }
}
