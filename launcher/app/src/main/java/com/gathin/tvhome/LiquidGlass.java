package com.gathin.tvhome;

import android.graphics.Canvas;
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
    private final float density;
    private float radius, focus = .25f;
    private int opacity = 255;
    LiquidGlass(float density, float radius) {
        this.density = density; this.radius = radius;
        edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(.55f * density);
    }
    void radius(float value) {
        if (radius == value) return;
        radius = value;
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
        area.set(bounds); area.inset(.6f * density, .6f * density);
        // Low contrast through the centre; highlights live at the curved perimeter, not in a glossy top stripe.
        fill.setShader(new LinearGradient(0, area.top, 0, Math.max(area.top + 1, area.bottom),
                new int[]{0x14FFFFFF, 0x0CFFFFFF, 0x18172027, 0x12172027},
                new float[]{0, .14f, .70f, 1}, Shader.TileMode.CLAMP));
        edge.setShader(new LinearGradient(area.left, area.top, area.right, area.bottom,
                new int[]{0x50FFFFFF, 0x0CFFFFFF, 0x05FFFFFF, 0x2CDAE7F2},
                new float[]{0, .26f, .70f, 1}, Shader.TileMode.CLAMP));
        lighting();
    }
    @Override public void draw(Canvas canvas) {
        // Three very faint, inset-free contours approximate a soft lift without allocating a blurred layer.
        for (int i = 3; i >= 1; i--) {
            shadeArea.set(area); shadeArea.inset(-i * density, -i * density);
            shadeArea.offset(0, 1.5f * density);
            shadow.setColor(android.graphics.Color.argb((4 - i) * 3 * opacity / 255, 0, 0, 0));
            canvas.drawRoundRect(shadeArea, radius + i * density, radius + i * density, shadow);
        }
        canvas.drawRoundRect(area, radius, radius, fill);
        canvas.drawRoundRect(area, radius, radius, light);
        canvas.drawRoundRect(area, radius, radius, edge);
    }
    @Override public void setAlpha(int value) {
        opacity = value; fill.setAlpha(value); edge.setAlpha(value); light.setAlpha(value); invalidateSelf();
    }
    @Override public void setColorFilter(ColorFilter filter) {
        fill.setColorFilter(filter); edge.setColorFilter(filter); light.setColorFilter(filter); invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
