package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

/** One small, blurred launcher snapshot shared by all control tiles. Never captures each frame. */
final class ControlBackdrop extends Drawable {
    private final View tile;
    private final float density, radius, screenScale;
    private final boolean hasBackdrop;
    private final boolean animate;
    private final int tintAlpha;
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint surface = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint[] edge = {new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG)};
    private static final int[] EDGE_ALPHA = {112, 52, 24};
    private final Paint focus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Path mask = new Path();
    private final Matrix matrix = new Matrix();
    private final int[] position = new int[2];
    private boolean focused;
    private float focusAmount;
    private ValueAnimator focusAnimator;
    private boolean positionFrozen;
    private int alpha = 255;

    ControlBackdrop(View tile, Bitmap backdrop, float density, float radius, float screenScale, int tone, boolean animate) {
        this.tile = tile; this.density = density;
        this.animate = animate;
        this.radius = radius; this.screenScale = screenScale;
        hasBackdrop = backdrop != null;
        if (hasBackdrop) background.setShader(new BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        else background.setColor(0xff29262f);
        // All light bands use the material's exact path. Clipping keeps the feather inside it.
        float[] widths = {1.0f, 3.0f, 7f};
        for (int i = 0; i < edge.length; i++) {
            edge[i].setStyle(Paint.Style.STROKE);
            edge[i].setStrokeWidth(widths[i] * density);
        }
        tint.setColor(tone);
        tintAlpha = tone >>> 24;
    }

    /** Preserve the sampled screen region before a fading page moves into a visual overlay. */
    static void freezePositions(View view) {
        if (view.getBackground() instanceof ControlBackdrop material) {
            view.getLocationOnScreen(material.position);
            material.positionFrozen = true;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) freezePositions(group.getChildAt(i));
        }
    }

    static Bitmap snapshot(View content) {
        int width = 160, height = Math.max(1, Math.round(content.getHeight() * (160f / content.getWidth())));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        // Also serves as the matte fallback when the window has no readable frame yet.
        bitmap.eraseColor(0xff29262f);
        return bitmap;
    }

    static void blur(Bitmap bitmap) {
        long blurStart = android.os.SystemClock.elapsedRealtime();
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height], scratch = new int[pixels.length];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        // Three separable box passes approximate Gaussian blur. Sliding sums keep work linear.
        final int radius = 2, count = radius * 2 + 1;
        for (int pass = 0; pass < 3; pass++) {
            for (int y = 0; y < height; y++) {
                int red = 0, green = 0, blue = 0, row = y * width;
                for (int x = -radius; x <= radius; x++) {
                    int c = pixels[row + Math.max(0, Math.min(width - 1, x))];
                    red += (c >>> 16) & 255; green += (c >>> 8) & 255; blue += c & 255;
                }
                for (int x = 0; x < width; x++) {
                    scratch[row + x] = 0xff000000 | (red / count << 16) | (green / count << 8) | blue / count;
                    int remove = pixels[row + Math.max(0, x - radius)];
                    int add = pixels[row + Math.min(width - 1, x + radius + 1)];
                    red += ((add >>> 16) & 255) - ((remove >>> 16) & 255);
                    green += ((add >>> 8) & 255) - ((remove >>> 8) & 255);
                    blue += (add & 255) - (remove & 255);
                }
            }
            for (int x = 0; x < width; x++) {
                int red = 0, green = 0, blue = 0;
                for (int y = -radius; y <= radius; y++) {
                    int c = scratch[Math.max(0, Math.min(height - 1, y)) * width + x];
                    red += (c >>> 16) & 255; green += (c >>> 8) & 255; blue += c & 255;
                }
                for (int y = 0; y < height; y++) {
                    pixels[y * width + x] = 0xff000000 | (red / count << 16) | (green / count << 8) | blue / count;
                    int remove = scratch[Math.max(0, y - radius) * width + x];
                    int add = scratch[Math.min(height - 1, y + radius + 1) * width + x];
                    red += ((add >>> 16) & 255) - ((remove >>> 16) & 255);
                    green += ((add >>> 8) & 255) - ((remove >>> 8) & 255);
                    blue += (add & 255) - (remove & 255);
                }
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        android.util.Log.d("PeachControls", "Worker blur " + (android.os.SystemClock.elapsedRealtime() - blurStart) + "ms");
    }

    @Override public boolean isStateful() { return true; }
    @Override protected boolean onStateChange(int[] states) {
        boolean next = false;
        for (int state : states) if (state == android.R.attr.state_focused) next = true;
        if (focused == next) return false;
        focused = next;
        if (!animate || !tile.isAttachedToWindow()) {
            jumpToCurrentState();
        } else {
            if (focusAnimator == null) {
                focusAnimator = new ValueAnimator();
                focusAnimator.setDuration(140);
                focusAnimator.addUpdateListener(value -> {
                    focusAmount = (float) value.getAnimatedValue();
                    tile.setTranslationZ(focusAmount * 3 * density);
                    invalidateSelf();
                });
            }
            focusAnimator.cancel();
            focusAnimator.setFloatValues(focusAmount, focused ? 1f : 0f);
            focusAnimator.start();
        }
        return true;
    }
    @Override public void jumpToCurrentState() {
        if (focusAnimator != null) focusAnimator.cancel();
        focusAmount = focused ? 1f : 0f;
        tile.setTranslationZ(focusAmount * 3 * density);
        invalidateSelf();
    }
    @Override public boolean setVisible(boolean visible, boolean restart) {
        if (!visible) jumpToCurrentState();
        return super.setVisible(visible, restart);
    }
    @Override public void getOutline(Outline outline) {
        if (mask.isEmpty()) { outline.setEmpty(); return; }
        outline.setConvexPath(mask);
        outline.setAlpha(.28f * alpha / 255);
    }
    @Override protected void onBoundsChange(Rect area) {
        bounds.set(area); bounds.inset(density, density);
        ContinuousCorners.set(mask, bounds.left, bounds.top, bounds.right, bounds.bottom, radius);
        surface.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                new int[]{0x20ffffff, 0x07ffffff, 0x02ffffff, 0x08000000},
                new float[]{0, .32f, .68f, 1}, Shader.TileMode.CLAMP));
        // Broad light and lower-edge shade suggest a curved surface, without a glossy stripe.
        focus.setShader(new RadialGradient(bounds.left + bounds.width() * .26f,
                bounds.top + bounds.height() * .08f, Math.max(bounds.width() * .85f, bounds.height()),
                new int[]{0x30ffffff, 0x0cffffff, 0x00ffffff}, new float[]{0, .48f, 1}, Shader.TileMode.CLAMP));
        shade.setShader(new LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom,
                new int[]{0x00000000, 0x03000000, 0x28000000},
                new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
        Shader light = new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                new int[]{0xa8ffffff, 0x22ffffff, 0x06ffffff, 0x60ffffff},
                new float[]{0, .35f, .7f, 1}, Shader.TileMode.CLAMP);
        for (Paint paint : edge) paint.setShader(light);
    }
    @Override public void draw(Canvas canvas) {
        if (bounds.isEmpty()) return;
        if (hasBackdrop) {
            if (!positionFrozen) tile.getLocationOnScreen(position);
            matrix.setScale(screenScale, screenScale);
            matrix.postTranslate(-position[0], -position[1]);
            background.getShader().setLocalMatrix(matrix);
        }
        canvas.drawPath(mask, background);
        canvas.drawPath(mask, tint);
        canvas.drawPath(mask, surface);
        focus.setAlpha(Math.round(alpha * (.35f + .65f * focusAmount)));
        shade.setAlpha(Math.round(alpha * (.55f + .45f * focusAmount)));
        canvas.drawPath(mask, focus);
        canvas.drawPath(mask, shade);
        int save = canvas.save();
        canvas.clipPath(mask);
        for (int i = edge.length - 1; i >= 0; i--) {
            edge[i].setAlpha(Math.round(alpha * EDGE_ALPHA[i] / 255f * (.42f + .58f * focusAmount)));
            canvas.drawPath(mask, edge[i]);
        }
        canvas.restoreToCount(save);
    }
    @Override public void setAlpha(int value) {
        alpha = value;
        background.setAlpha(value); tint.setAlpha(value * tintAlpha / 255);
        surface.setAlpha(value);
        invalidateSelf();
    }
    @Override public void setColorFilter(ColorFilter value) { background.setColorFilter(value); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
