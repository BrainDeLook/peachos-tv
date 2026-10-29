package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Shared continuous mask for banners and square Android app icons. No per-frame geometry or buffers. */
public final class AppTile extends FrameLayout {
    private final Path mask = new Path();
    public AppTile(Context context, AttributeSet attrs) {
        super(context, attrs);
        // Opaque banners remove the background; keep draw() active so the shared mask still clips children.
        setWillNotDraw(false);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        ContinuousCorners.set(mask, 0, 0, w, h, AppAdapter.cornerRadius(w, getResources().getDisplayMetrics().density));
    }
    @Override public void draw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(mask);
        super.draw(canvas);
        canvas.restoreToCount(save);
    }

    /** Three faint, overlapping light bands feather inward; no hard rim or dark bevel. */
    static final class Edge extends Drawable {
        private final Path[] bands = {new Path(), new Path(), new Path()};
        private final Paint[] light = {new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG)};
        private static final int[] STRENGTH = {45, 30, 16};
        private final float density, fixedRadius;
        Edge(float density) { this(density, 0); }
        Edge(float density, float fixedRadius) {
            this.density = density; this.fixedRadius = fixedRadius;
            float[] widths = {1.2f, 2.4f, 4.8f};
            for (int i = 0; i < light.length; i++) {
                light[i].setStyle(Paint.Style.STROKE);
                light[i].setStrokeWidth(widths[i] * density);
                light[i].setAlpha(STRENGTH[i]);
            }
        }
        @Override protected void onBoundsChange(Rect b) {
            float radius = fixedRadius > 0 ? fixedRadius : AppAdapter.cornerRadius(b.width(), density);
            float[] insets = {.6f, 1.8f, 3f};
            Shader illumination = new LinearGradient(b.left, b.top, b.right, b.bottom,
                    new int[]{0x20ffffff, 0x06ffffff, 0x00ffffff, 0x12ffffff},
                    new float[]{0, .35f, .7f, 1}, Shader.TileMode.CLAMP);
            for (int i = 0; i < bands.length; i++) {
                float inset = insets[i] * density;
                ContinuousCorners.set(bands[i], b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, radius - inset);
                light[i].setShader(illumination);
            }
        }
        @Override public void draw(Canvas canvas) {
            for (int i = bands.length - 1; i >= 0; i--) canvas.drawPath(bands[i], light[i]);
        }
        @Override public void setAlpha(int alpha) {
            for (int i = 0; i < light.length; i++) light[i].setAlpha(alpha * STRENGTH[i] / 255);
            invalidateSelf();
        }
        @Override public void setColorFilter(ColorFilter filter) {
            for (Paint paint : light) paint.setColorFilter(filter);
            invalidateSelf();
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
