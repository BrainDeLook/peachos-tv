package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** A quiet, TV-sized YouTube brand card, drawn locally without thumbnails or downloads. */
final class YouTubeBackdrop extends Drawable {
    private static final Typeface LOGO_FONT = Typeface.create("sans-serif", Typeface.BOLD);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path play = new Path();
    private Shader glow;
    private float left, top, width, height, radius, textX, textY;
    private int alpha = 255;

    YouTubeBackdrop() {
        paint.setTypeface(LOGO_FONT);
    }

    @Override protected void onBoundsChange(Rect b) {
        if (b.isEmpty()) return;
        float unit = b.width() / 1920f;
        glow = new RadialGradient(b.left + b.width() * .55f, b.top + b.height() * .40f,
                b.width() * .57f, new int[]{0xFF330A0D, 0xFF110305, Color.BLACK},
                new float[]{0f, .60f, 1f}, Shader.TileMode.CLAMP);
        left = b.left + b.width() * .43f; top = b.top + b.height() * .35f;
        width = 210f * unit; height = 146f * unit; radius = 37f * unit;
        play.rewind();
        play.moveTo(left + width * .42f, top + height * .27f);
        play.lineTo(left + width * .42f, top + height * .73f);
        play.lineTo(left + width * .72f, top + height * .50f);
        play.close();
        paint.setTextSize(115f * unit);
        textX = left + width + 30f * unit; textY = top + height * .78f;
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        paint.setShader(null);
        paint.setColor(Color.BLACK);
        paint.setAlpha(alpha);
        canvas.drawRect(b, paint);
        paint.setShader(glow);
        canvas.drawRect(b, paint);
        paint.setShader(null);
        paint.setColor(0xFFFF0033);
        paint.setAlpha(alpha);
        canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, paint);
        paint.setColor(Color.WHITE);
        paint.setAlpha(alpha);
        canvas.drawPath(play, paint);
        canvas.drawText("YouTube", textX, textY, paint);
    }

    @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
