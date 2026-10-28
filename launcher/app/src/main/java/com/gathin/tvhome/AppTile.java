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

    /** A narrow, low-contrast light roll at the selected icon's perimeter; the artwork stays untouched. */
    static final class Edge extends Drawable {
        private final Path outer = new Path(), inner = new Path();
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG), roll = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        Edge(float density) {
            this.density = density;
            rim.setStyle(Paint.Style.STROKE); rim.setStrokeWidth(.65f * density);
            roll.setStyle(Paint.Style.STROKE); roll.setStrokeWidth(1.8f * density);
        }
        @Override protected void onBoundsChange(Rect b) {
            float radius = AppAdapter.cornerRadius(b.width(), density), inset = .7f * density;
            ContinuousCorners.set(outer, b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, radius - inset);
            inset = 1.8f * density;
            ContinuousCorners.set(inner, b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, radius - inset);
            rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                    new int[]{0x55ffffff, 0x0affffff, 0x06ffffff, 0x2effffff},
                    new float[]{0, .35f, .7f, 1}, Shader.TileMode.CLAMP));
            roll.setShader(new LinearGradient(0, b.top, 0, Math.max(b.top + 1, b.bottom),
                    new int[]{0x18ffffff, 0x00ffffff, 0x18000000}, new float[]{0, .4f, 1}, Shader.TileMode.CLAMP));
        }
        @Override public void draw(Canvas canvas) { canvas.drawPath(inner, roll); canvas.drawPath(outer, rim); }
        @Override public void setAlpha(int alpha) { rim.setAlpha(alpha); roll.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { rim.setColorFilter(filter); roll.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
