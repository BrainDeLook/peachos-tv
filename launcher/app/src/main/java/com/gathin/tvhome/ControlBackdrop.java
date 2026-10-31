package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
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
import android.widget.TextView;
import android.widget.ImageView;
import android.content.res.ColorStateList;
import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import java.util.ArrayList;

/** One small, blurred launcher snapshot shared by all control tiles. Never captures each frame. */
final class ControlBackdrop extends Drawable {
    private static final int LIGHTING_CACHE_BYTES = 2 * 1024 * 1024;
    private static final long MAX_LIGHTING_ENTRY_BYTES = 1024 * 1024;
    private static final class LightingKey {
        final int width, height, density, radius;
        LightingKey(int width, int height, float density, float radius) {
            this.width = width; this.height = height;
            this.density = Float.floatToIntBits(density); this.radius = Float.floatToIntBits(radius);
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof LightingKey key)) return false;
            return width == key.width && height == key.height && density == key.density && radius == key.radius;
        }
        @Override public int hashCode() { return ((width * 31 + height) * 31 + density) * 31 + radius; }
    }
    private static final class Lighting {
        final Bitmap[] layers;
        final int bytes;
        Lighting(Bitmap[] layers) {
            this.layers = layers;
            int size = 0;
            for (Bitmap bitmap : layers) size += bitmap.getAllocationByteCount();
            bytes = size;
        }
    }
    private static final android.util.LruCache<LightingKey, Lighting> LIGHTING_CACHE =
            new android.util.LruCache<>(LIGHTING_CACHE_BYTES) {
                @Override protected int sizeOf(LightingKey key, Lighting value) { return value.bytes; }
            };
    private final View tile;
    private final float density, radius, screenScale;
    private final boolean hasBackdrop, translucent;
    private final boolean animate;
    private final int tintAlpha, baseAlpha;
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint surface = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint[] edge = {new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG)};
    private static final int[] EDGE_ALPHA = {112, 52, 24};
    private final Paint focus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint raster = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Lighting lighting;
    private int lightingLeft, lightingTop;
    private final RectF bounds = new RectF();
    private final Path mask = new Path();
    private final Matrix matrix = new Matrix();
    private final int[] position = new int[2];
    private boolean focused;
    private float focusAmount;
    private ValueAnimator focusAnimator;
    private boolean whiteFocus;
    private SpringAnimation focusSpring;
    private final Paint selection = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<TextView> focusText = new ArrayList<>();
    private final ArrayList<Integer> textColors = new ArrayList<>();
    private final ArrayList<ImageView> focusImages = new ArrayList<>();
    private final ArrayList<Integer> imageColors = new ArrayList<>();
    private boolean contentReady;
    private static final FloatPropertyCompat<ControlBackdrop> FOCUS = new FloatPropertyCompat<ControlBackdrop>("whiteControlFocus") {
        @Override public float getValue(ControlBackdrop value) { return value.focusAmount; }
        @Override public void setValue(ControlBackdrop value, float amount) { value.applyWhiteFocus(amount); }
    };
    private boolean positionFrozen;
    private int sampledX = Integer.MIN_VALUE, sampledY = Integer.MIN_VALUE;
    private int alpha = 255;

    ControlBackdrop(View tile, Bitmap backdrop, float density, float radius, float screenScale, int tone, boolean animate, boolean translucent) {
        this.tile = tile; this.density = density;
        this.animate = animate; this.translucent = translucent;
        this.radius = radius; this.screenScale = screenScale;
        hasBackdrop = backdrop != null;
        baseAlpha = translucent && hasBackdrop ? 176 : 255;
        if (hasBackdrop) background.setShader(new BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        else background.setColor(translucent ? 0x10ffffff : 0xff29262f);
        // All light bands use the material's exact path. Clipping keeps the feather inside it.
        float[] widths = {1.0f, 3.0f, 7f};
        for (int i = 0; i < edge.length; i++) {
            edge[i].setStyle(Paint.Style.STROKE);
            edge[i].setStrokeWidth(widths[i] * density);
        }
        tint.setColor(translucent ? (hasBackdrop ? 0x66211d25 : 0x80211d25) : tone);
        tintAlpha = tint.getColor() >>> 24;
        background.setAlpha(baseAlpha);
    }

    static void trimLightingCache() { LIGHTING_CACHE.evictAll(); }

    ControlBackdrop whiteFocus() { whiteFocus = true; selection.setColor(Color.WHITE); return this; }

    private void rememberContent(View view) {
        if (view instanceof TextView text) {
            focusText.add(text); textColors.add(text.getTextColors().getDefaultColor());
            text.setDuplicateParentStateEnabled(false);
        } else if (view instanceof ImageView image) {
            focusImages.add(image);
            ColorStateList tint = image.getImageTintList();
            imageColors.add(tint == null ? 0xe6ffffff : tint.getDefaultColor());
            image.setDuplicateParentStateEnabled(false);
        }
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) rememberContent(group.getChildAt(i));
    }

    private void applyWhiteFocus(float value) {
        focusAmount = value;
        float fraction = Math.max(0f, Math.min(1f, value));
        tile.setTranslationZ(fraction * 3 * density);
        tile.setScaleX(1f + .035f * value); tile.setScaleY(1f + .035f * value);
        for (int i = 0; i < focusText.size(); i++) {
            TextView text = focusText.get(i);
            int color = ColorUtils.blendARGB(textColors.get(i), 0xff151515, fraction);
            if (text.getCurrentTextColor() != color) text.setTextColor(color);
        }
        for (int i = 0; i < focusImages.size(); i++) {
            ImageView image = focusImages.get(i);
            int color = ColorUtils.blendARGB(imageColors.get(i), 0xff151515, fraction);
            ColorStateList tint = image.getImageTintList();
            if (tint == null || tint.getDefaultColor() != color) image.setImageTintList(ColorStateList.valueOf(color));
        }
        invalidateSelf();
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

    /** A cached page samples its new screen position again when reattached. */
    static void restorePositions(View view) {
        if (view.getBackground() instanceof ControlBackdrop material) {
            material.positionFrozen = false;
            if (material.hasBackdrop) {
                view.getLocationOnScreen(material.position);
                if (material.sampledX != material.position[0] || material.sampledY != material.position[1]) {
                    material.invalidateSelf();
                }
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) restorePositions(group.getChildAt(i));
        }
    }

    /** Detached pages must not keep scheduling focus/scale frames after their overlay disappears. */
    static void finishAnimations(View view) {
        view.animate().cancel();
        if (view.getBackground() instanceof ControlBackdrop material) {
            material.jumpToCurrentState();
            view.setScaleX(1f); view.setScaleY(1f);
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) finishAnimations(group.getChildAt(i));
        }
    }

    static Bitmap snapshot(View content) {
        android.util.DisplayMetrics screen = content.getResources().getDisplayMetrics();
        int sourceWidth = content.getWidth() > 0 ? content.getWidth() : screen.widthPixels;
        int sourceHeight = content.getHeight() > 0 ? content.getHeight() : screen.heightPixels;
        int width = 160, height = Math.max(1, Math.round(sourceHeight * (160f / sourceWidth)));
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
        if (whiteFocus) {
            if (!contentReady) { rememberContent(tile); contentReady = true; }
            if (!animate || !tile.isAttachedToWindow()) jumpToCurrentState();
            else {
                if (focusSpring == null) {
                    focusSpring = new SpringAnimation(this, FOCUS);
                    focusSpring.setMinimumVisibleChange(.001f);
                    focusSpring.setSpring(new SpringForce());
                }
                focusSpring.getSpring().setStiffness(focused ? 1500f : 1900f)
                        .setDampingRatio(focused ? .72f : 1f);
                focusSpring.animateToFinalPosition(focused ? 1f : 0f);
            }
            return true;
        }
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
        if (focusSpring != null) focusSpring.cancel();
        if (focusAnimator != null) focusAnimator.cancel();
        float target = focused ? 1f : 0f;
        if (whiteFocus) { if (focusAmount != target) applyWhiteFocus(target); return; }
        if (focusAmount == target) return;
        focusAmount = target;
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
        if (translucent && !hasBackdrop) background.setShader(new LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom,
                0x10ffffff, 0x0affffff, Shader.TileMode.CLAMP));
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
        lightingLeft = area.left; lightingTop = area.top;
        // Home retains its existing GPU material. Only the in-app window avoids compiling six
        // clipped-gradient GPU programs during its first visible frame.
        lighting = translucent ? prepareLighting(area) : null;
    }

    private Lighting prepareLighting(Rect area) {
        int width = area.width(), height = area.height();
        // Only the surface mixes white and black. The five other layers have a constant RGB
        // colour, so alpha masks preserve their gradients at one byte per pixel instead of four.
        if (width <= 0 || height <= 0 || (long) width * height > MAX_LIGHTING_ENTRY_BYTES / (4 + 5)) return null;
        LightingKey key = new LightingKey(width, height, density, radius);
        Lighting cached = LIGHTING_CACHE.get(key);
        if (cached != null) return cached;
        long started = android.os.SystemClock.uptimeMillis();
        Paint[] source = {surface, focus, shade, edge[0], edge[1], edge[2]};
        Bitmap[] layers = new Bitmap[source.length];
        for (int i = 0; i < layers.length; i++) {
            Bitmap bitmap = Bitmap.createBitmap(width, height, i == 0 ? Bitmap.Config.ARGB_8888 : Bitmap.Config.ALPHA_8);
            bitmap.setDensity(Bitmap.DENSITY_NONE);
            Canvas software = new Canvas(bitmap);
            software.translate(-area.left, -area.top);
            if (i >= 3) software.clipPath(mask);
            Paint paint = new Paint(source[i]);
            // Keep shader-stop alpha in the image; dynamic focus/global/rim alpha is applied at
            // draw time. Baking EDGE_ALPHA here as well would dim the rim twice.
            paint.setAlpha(255);
            software.drawPath(mask, paint);
            layers[i] = bitmap;
        }
        Lighting result = new Lighting(layers);
        if (result.bytes > MAX_LIGHTING_ENTRY_BYTES) return null;
        LIGHTING_CACHE.put(key, result);
        android.util.Log.d("PeachOverlay", "Lighting raster " + width + "x" + height + " "
                + result.bytes + "bytes " + (android.os.SystemClock.uptimeMillis() - started) + "ms");
        return result;
    }
    @Override public void draw(Canvas canvas) {
        if (bounds.isEmpty()) return;
        if (hasBackdrop) {
            if (!positionFrozen) tile.getLocationOnScreen(position);
            if (sampledX != position[0] || sampledY != position[1]) {
                sampledX = position[0]; sampledY = position[1];
                matrix.setScale(screenScale, screenScale);
                matrix.postTranslate(-sampledX, -sampledY);
                background.getShader().setLocalMatrix(matrix);
            }
        }
        canvas.drawPath(mask, background);
        canvas.drawPath(mask, tint);
        if (whiteFocus && focusAmount > 0f) {
            selection.setAlpha(Math.round(alpha * .98f * Math.min(1f, focusAmount)));
            canvas.drawPath(mask, selection);
        }
        float amount = Math.max(0f, Math.min(1f, focusAmount));
        focus.setAlpha(Math.round(alpha * (.35f + .65f * amount)));
        shade.setAlpha(Math.round(alpha * (.55f + .45f * amount)));
        for (int i = 0; i < edge.length; i++) {
            edge[i].setAlpha(Math.round(alpha * EDGE_ALPHA[i] / 255f * (.42f + .58f * amount)));
        }
        if (lighting != null) {
            raster.setColor(Color.WHITE);
            raster.setAlpha(surface.getAlpha());
            canvas.drawBitmap(lighting.layers[0], lightingLeft, lightingTop, raster);
            raster.setAlpha(focus.getAlpha());
            canvas.drawBitmap(lighting.layers[1], lightingLeft, lightingTop, raster);
            raster.setColor(Color.BLACK);
            raster.setAlpha(shade.getAlpha());
            canvas.drawBitmap(lighting.layers[2], lightingLeft, lightingTop, raster);
            raster.setColor(Color.WHITE);
            for (int i = edge.length - 1; i >= 0; i--) {
                raster.setAlpha(edge[i].getAlpha());
                canvas.drawBitmap(lighting.layers[3 + i], lightingLeft, lightingTop, raster);
            }
            return;
        }
        canvas.drawPath(mask, surface);
        canvas.drawPath(mask, focus);
        canvas.drawPath(mask, shade);
        int save = canvas.save();
        canvas.clipPath(mask);
        for (int i = edge.length - 1; i >= 0; i--) {
            canvas.drawPath(mask, edge[i]);
        }
        canvas.restoreToCount(save);
    }
    @Override public void setAlpha(int value) {
        alpha = value;
        background.setAlpha(value * baseAlpha / 255); tint.setAlpha(value * tintAlpha / 255);
        surface.setAlpha(value);
        invalidateSelf();
    }
    @Override public void setColorFilter(ColorFilter value) { background.setColorFilter(value); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
