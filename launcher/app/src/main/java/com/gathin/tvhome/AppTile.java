package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RadialGradient;
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

    /** Broad top illumination and feathered edges share the artwork's continuous mask. */
    static final class Edge extends Drawable {
        private final Path surface = new Path();
        private final Paint surfaceLight = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint surfaceShade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path[] bands = {new Path(), new Path(), new Path()};
        private final Paint[] light = {new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG)};
        private static final int[] STRENGTH = {70, 18, 5};
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
            ContinuousCorners.set(surface, b.left, b.top, b.right, b.bottom, radius);
            // A wide soft light above the face, not a hard gloss stripe. Shaders are rebuilt only
            // on resize; focus fades the existing render node alongside its scale animation.
            RadialGradient topLight = new RadialGradient(0, 0, 1,
                    new int[]{0x46ffffff, 0x28ffffff, 0x00ffffff},
                    new float[]{0, .42f, 1}, Shader.TileMode.CLAMP);
            Matrix ellipse = new Matrix();
            ellipse.setScale(b.width() * .85f, b.height() * 1.10f);
            ellipse.postTranslate(b.exactCenterX(), b.top - b.height() * .22f);
            topLight.setLocalMatrix(ellipse);
            surfaceLight.setShader(topLight);
            surfaceShade.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    new int[]{0x00000000, 0x00000000, 0x10000000},
                    new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
            Shader illumination = new LinearGradient(b.left, b.top, b.right, b.bottom,
                    new int[]{0x80ffffff, 0x18ffffff, 0x00ffffff, 0x2dffffff},
                    new float[]{0, .35f, .7f, 1}, Shader.TileMode.CLAMP);
            for (int i = 0; i < bands.length; i++) {
                // Coincident strokes feather from the same silhouette; inset contours read
                // as several separate borders instead of a single softly lit curved edge.
                ContinuousCorners.set(bands[i], b.left, b.top, b.right, b.bottom, radius);
                light[i].setShader(illumination);
            }
        }
        @Override public void draw(Canvas canvas) {
            canvas.drawPath(surface, surfaceShade);
            canvas.drawPath(surface, surfaceLight);
            for (int i = bands.length - 1; i >= 0; i--) canvas.drawPath(bands[i], light[i]);
        }
        @Override public void setAlpha(int alpha) {
            surfaceLight.setAlpha(alpha);
            surfaceShade.setAlpha(alpha);
            for (int i = 0; i < light.length; i++) light[i].setAlpha(alpha * STRENGTH[i] / 255);
            invalidateSelf();
        }
        @Override public void setColorFilter(ColorFilter filter) {
            surfaceLight.setColorFilter(filter);
            surfaceShade.setColorFilter(filter);
            for (Paint paint : light) paint.setColorFilter(filter);
            invalidateSelf();
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
