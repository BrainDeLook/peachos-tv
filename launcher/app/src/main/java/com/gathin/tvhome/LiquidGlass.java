package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** A light glass approximation: two cached gradients, no blur, frame capture, offscreen bitmap or timer. */
final class LiquidGlass extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF area = new RectF();
    private final float density;
    private float radius;
    LiquidGlass(float density, float radius) {
        this.density = density; this.radius = radius;
        edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(.8f * density);
    }
    void radius(float value) { radius = value; }
    @Override protected void onBoundsChange(Rect bounds) {
        area.set(bounds); area.inset(.6f * density, .6f * density);
        fill.setShader(new LinearGradient(0, bounds.top, 0, Math.max(bounds.top + 1, bounds.bottom),
                new int[]{0x32FFFFFF, 0x201C2632, 0x1C121922, 0x247894AF},
                new float[]{0, .18f, .65f, 1}, Shader.TileMode.CLAMP));
        edge.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                new int[]{0x80FFFFFF, 0x12FFFFFF, 0x38D8E9FF}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
    }
    @Override public void draw(Canvas canvas) {
        canvas.drawRoundRect(area, radius, radius, fill);
        canvas.drawRoundRect(area, radius, radius, edge);
    }
    @Override public void setAlpha(int value) { fill.setAlpha(value); edge.setAlpha(value); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { fill.setColorFilter(filter); edge.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
