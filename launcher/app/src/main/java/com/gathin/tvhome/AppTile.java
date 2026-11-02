package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.BitmapShader;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
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
import android.util.LruCache;
import android.view.View;
import android.widget.FrameLayout;

/** Shared continuous mask for banners and square Android app icons. No per-frame geometry or buffers. */
public final class AppTile extends FrameLayout {
    private final Path mask = new Path();
    public AppTile(Context context, AttributeSet attrs) {
        super(context, attrs);
        // Opaque banners remove the background; keep draw() active so the shared mask still clips children.
        setWillNotDraw(false);
        // Static tiles otherwise replay their continuous mask and lighting paths
        // on every frame of a neighbouring tile's animation. Keep only attached tile textures.
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
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

    /** Small blurred silhouette, shared by equal-sized tiles; never blurred during animation. */
    static final class Glow extends Drawable {
        private static final LruCache<String, Bitmap> MASKS = new LruCache<String, Bitmap>(256 * 1024) {
            @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
        };
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private static final LruCache<java.util.List<Object>, Bitmap> COLORS = new LruCache<java.util.List<Object>, Bitmap>(512 * 1024) {
            @Override protected int sizeOf(java.util.List<Object> key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
        };
        private final Bitmap mask;
        private final int width, height, padding;
        private Bitmap colored;
        Glow(int width, int height, float density, int padding) {
            this.width = width; this.height = height; this.padding = padding;
            String key = width + ":" + height + ":" + density + ":" + padding;
            Bitmap cached = MASKS.get(key);
            if (cached == null) {
                cached = Bitmap.createBitmap(width + padding * 2, height + padding * 2, Bitmap.Config.ALPHA_8);
                cached.setDensity(Bitmap.DENSITY_NONE);
                Path contour = new Path();
                ContinuousCorners.set(contour, padding, padding + density * 1.5f,
                        padding + width, padding + height + density * 1.5f, AppAdapter.cornerRadius(width, density));
                Paint soft = new Paint(Paint.ANTI_ALIAS_FLAG);
                soft.setColor(0xffffffff);
                soft.setMaskFilter(new BlurMaskFilter(2.5f * density, BlurMaskFilter.Blur.NORMAL));
                new Canvas(cached).drawPath(contour, soft);
                MASKS.put(key, cached);
            }
            mask = cached;
        }
        static void trimCache() { MASKS.evictAll(); COLORS.evictAll(); }
        void clearArtwork() { colored = null; }
        void artwork(Object entry, Drawable banner, Drawable background) {
            java.util.List<Object> key = java.util.Arrays.asList(entry, width, height, padding);
            colored = COLORS.get(key);
            if (colored == null) {
                // Clamp the actual edge pixels into the feather, preserving different colours
                // along each side instead of choosing a saturated accent from the logo centre.
                Bitmap art = Bitmap.createBitmap(48, Math.max(1, Math.round(48f * height / width)), Bitmap.Config.ARGB_8888);
                Canvas sample = new Canvas(art);
                Drawable source = banner != null ? banner : background;
                if (source != null) {
                    Rect old = new Rect(source.getBounds());
                    int dw = source.getIntrinsicWidth(), dh = source.getIntrinsicHeight();
                    if (banner != null && dw > 0 && dh > 0) {
                        float scale = Math.max(art.getWidth() / (float) dw, art.getHeight() / (float) dh);
                        sample.translate((art.getWidth() - dw * scale) * .5f, (art.getHeight() - dh * scale) * .5f);
                        sample.scale(scale, scale); source.setBounds(0, 0, dw, dh);
                    } else source.setBounds(0, 0, art.getWidth(), art.getHeight());
                    source.draw(sample); source.setBounds(old);
                }
                colored = Bitmap.createBitmap(Math.max(1, mask.getWidth() / 2), Math.max(1, mask.getHeight() / 2), Bitmap.Config.ARGB_8888);
                colored.setDensity(Bitmap.DENSITY_NONE);
                Canvas raster = new Canvas(colored);
                raster.scale(colored.getWidth() / (float) mask.getWidth(), colored.getHeight() / (float) mask.getHeight());
                BitmapShader edgeColor = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix position = new Matrix(); position.setScale(width / (float) art.getWidth(), height / (float) art.getHeight());
                position.postTranslate(padding, padding); edgeColor.setLocalMatrix(position);
                Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                fill.setColor(0xffffffff);
                raster.drawBitmap(mask, 0, 0, fill);
                fill.setShader(edgeColor); fill.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
                raster.drawRect(0, 0, mask.getWidth(), mask.getHeight(), fill);
                COLORS.put(key, colored);
            }
            invalidateSelf();
        }
        @Override public void draw(Canvas canvas) { if (colored != null) canvas.drawBitmap(colored, null, getBounds(), paint); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
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
                    new int[]{0x90ffffff, 0x16ffffff, 0x00ffffff, 0x60ffffff},
                    new float[]{0, .18f, .78f, 1}, Shader.TileMode.CLAMP);
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
