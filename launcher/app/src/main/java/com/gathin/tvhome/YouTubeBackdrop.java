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
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 255;

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        float unit = b.width() / 1920f;
        paint.setShader(null);
        paint.setColor(Color.BLACK);
        paint.setAlpha(alpha);
        canvas.drawRect(b, paint);
        paint.setShader(new RadialGradient(b.width() * .55f, b.height() * .40f,
                b.width() * .57f, new int[]{0xFF330A0D, 0xFF110305, Color.BLACK},
                new float[]{0f, .60f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(b, paint);
        paint.setShader(null);
        float left = b.width() * .43f, top = b.height() * .35f;
        float width = 210f * unit, height = 146f * unit;
        paint.setColor(0xFFFF0033);
        paint.setAlpha(alpha);
        canvas.drawRoundRect(left, top, left + width, top + height, 37f * unit, 37f * unit, paint);
        Path play = new Path();
        play.moveTo(left + width * .42f, top + height * .27f);
        play.lineTo(left + width * .42f, top + height * .73f);
        play.lineTo(left + width * .72f, top + height * .50f);
        play.close();
        paint.setColor(Color.WHITE);
        paint.setAlpha(alpha);
        canvas.drawPath(play, paint);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paint.setTextSize(115f * unit);
        canvas.drawText("YouTube", left + width + 30f * unit, top + height * .78f, paint);
    }

    @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
