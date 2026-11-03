package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.text.TextUtils;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

/** Read the focused SSID once to its end, pause, then repeat from the beginning. */
@android.annotation.SuppressLint("ViewConstructor") // Created by the Wi-Fi row with its motion policy.
final class WifiNameView extends TextView {
    private final boolean smooth;
    private boolean reading;
    private int readDistance;
    private ValueAnimator motion;
    private final Runnable begin = this::beginScroll;
    private final Runnable restart = () -> {
        scrollTo(0, 0);
        postDelayed(begin, 700);
    };

    WifiNameView(Context context, boolean smooth) {
        super(context);
        this.smooth = smooth;
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setFadingEdgeLength(Math.round(12 * getResources().getDisplayMetrics().density));
    }

    void setReading(boolean value) {
        if (reading == value) return;
        reading = value;
        resetScroll();
        if (reading && smooth) postDelayed(begin, 700);
    }

    private void beginScroll() {
        if (!reading || !isShown() || getWindowVisibility() != VISIBLE) return;
        int available = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        if (available <= 0) return;
        int distance = (int) Math.ceil(getPaint().measureText(getText(), 0, length()) - available);
        if (distance <= 0) return;
        readDistance = distance;
        setHorizontalFadingEdgeEnabled(true);
        setEllipsize(null);
        if (motion == null) {
            motion = new ValueAnimator();
            motion.setInterpolator(new LinearInterpolator());
            motion.addUpdateListener(value -> scrollTo((Integer) value.getAnimatedValue(), 0));
            motion.addListener(new AnimatorListenerAdapter() {
                private boolean canceled;
                @Override public void onAnimationStart(Animator animation) { canceled = false; }
                @Override public void onAnimationCancel(Animator animation) { canceled = true; }
                @Override public void onAnimationEnd(Animator animation) {
                    if (!canceled && reading) postDelayed(restart, 1200);
                }
            });
        }
        motion.setIntValues(0, distance);
        motion.setDuration(Math.max(600, Math.round(distance * 1000f
                / (30f * getResources().getDisplayMetrics().density))));
        motion.start();
    }

    private void resetScroll() {
        removeCallbacks(begin);
        removeCallbacks(restart);
        if (motion != null) motion.cancel();
        readDistance = 0;
        setHorizontalFadingEdgeEnabled(false);
        scrollTo(0, 0);
        setEllipsize(TextUtils.TruncateAt.END);
    }

    @Override protected float getLeftFadingEdgeStrength() {
        return Math.min(1f, getScrollX() / (float) getHorizontalFadingEdgeLength());
    }

    @Override protected float getRightFadingEdgeStrength() {
        return Math.max(0f, Math.min(1f,
                (readDistance - getScrollX()) / (float) getHorizontalFadingEdgeLength()));
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width != oldWidth) {
            resetScroll();
            if (reading && smooth) postDelayed(begin, 700);
        }
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        resetScroll();
        if (visible && reading && smooth) postDelayed(begin, 700);
    }

    @Override protected void onDetachedFromWindow() {
        resetScroll();
        super.onDetachedFromWindow();
    }
}
