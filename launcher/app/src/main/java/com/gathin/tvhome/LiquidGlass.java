package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Restrained navigation material. Cached geometry and light; no backdrop capture or blur buffers. */
final class LiquidGlass extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG), shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF area = new RectF(), shadeArea = new RectF();
    private final RectF cachedBounds = new RectF();
    private final Paint cachedPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final float density;
    private final boolean cacheBase;
    private Bitmap base;
    private boolean baseDirty = true;
    private float radius, focus = .25f;
    private int opacity = 255;
    private int width = -1, height = -1;
    LiquidGlass(float density, float radius) {
        this(density, radius, false);
    }
    LiquidGlass(float density, float radius, boolean cacheBase) {
        this.density = density; this.radius = radius;
        this.cacheBase = cacheBase;
        edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(.55f * density);
    }
    void radius(float value) {
        if (radius == value) return;
        radius = value;
        baseDirty = true;
    }
    void focus(float value) {
        if (Math.abs(focus - value) < .001f) return;
        focus = value; lighting();
    }
    private void lighting() {
        if (area.isEmpty()) return;
        light.setShader(new RadialGradient(area.left + area.width() * focus, area.top,
                Math.max(area.height() * 1.5f, area.width() * .28f),
                new int[]{0x10FFFFFF, 0x04E5F1FF, 0x00FFFFFF}, new float[]{0, .45f, 1}, Shader.TileMode.CLAMP));
    }
    @Override protected void onBoundsChange(Rect bounds) {
        // Scrolling moves the dock without changing its shape: keep shaders in local coordinates.
        if (width == bounds.width() && height == bounds.height()) return;
        width = bounds.width(); height = bounds.height();
        baseDirty = true;
        float padding = (float) Math.ceil(density * 5);
        cachedBounds.set(-padding, -padding, width + padding, height + padding);
        area.set(0, 0, width, height); area.inset(.6f * density, .6f * density);
        if (area.isEmpty()) return;
        // Low contrast through the centre; highlights live at the curved perimeter, not in a glossy top stripe.
        fill.setShader(new LinearGradient(0, area.top, 0, Math.max(area.top + 1, area.bottom),
                new int[]{0x1CFFFFFF, 0x18FFFFFF, 0x12FFFFFF, 0x10FFFFFF},
                new float[]{0, .14f, .70f, 1}, Shader.TileMode.CLAMP));
        edge.setShader(new LinearGradient(area.left, area.top, area.right, area.bottom,
                new int[]{0x38C8DBEF, 0x16FFFFFF, 0x0AFFFFFF, 0x20B9CBDD},
                new float[]{0, .26f, .70f, 1}, Shader.TileMode.CLAMP));
        lighting();
    }
    @Override public void draw(Canvas canvas) {
        if (area.isEmpty()) return;
        int save = canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        if (cacheBase) {
            if (baseDirty) {
                // Only the smooth fill and soft shadows are downsampled. The light and sharp rim stay native.
                base = Bitmap.createBitmap((int) Math.ceil(cachedBounds.width() * .5f),
                        (int) Math.ceil(cachedBounds.height() * .5f), Bitmap.Config.ARGB_8888);
                Canvas recording = new Canvas(base);
                recording.scale(base.getWidth() / cachedBounds.width(), base.getHeight() / cachedBounds.height());
                recording.translate(-cachedBounds.left, -cachedBounds.top);
                drawBase(recording);
                baseDirty = false;
                android.util.Log.d("PeachRender", "Dock base cache " + base.getWidth() + "x"
                        + base.getHeight() + ", " + base.getAllocationByteCount() + " bytes");
            }
            canvas.drawBitmap(base, null, cachedBounds, cachedPaint);
        } else drawBase(canvas);
        canvas.drawRoundRect(area, radius, radius, light);
        canvas.drawRoundRect(area, radius, radius, edge);
        canvas.restoreToCount(save);
    }
    private void drawBase(Canvas canvas) {
        // Three very faint, inset-free contours approximate a soft lift without allocating a blurred layer.
        for (int i = 3; i >= 1; i--) {
            shadeArea.set(area); shadeArea.inset(-i * density, -i * density);
            shadeArea.offset(0, 1.5f * density);
            shadow.setColor(android.graphics.Color.argb((4 - i) * 3 * opacity / 255, 0, 0, 0));
            canvas.drawRoundRect(shadeArea, radius + i * density, radius + i * density, shadow);
        }
        canvas.drawRoundRect(area, radius, radius, fill);
    }
    @Override public void setAlpha(int value) {
        opacity = value; fill.setAlpha(value); edge.setAlpha(value); light.setAlpha(value); invalidateSelf();
        baseDirty = true;
    }
    @Override public void setColorFilter(ColorFilter filter) {
        fill.setColorFilter(filter); edge.setColorFilter(filter); light.setColorFilter(filter); invalidateSelf();
        baseDirty = true;
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
