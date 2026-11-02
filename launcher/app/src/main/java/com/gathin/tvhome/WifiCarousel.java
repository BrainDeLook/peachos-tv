package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Canvas;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** A bounded AP list with one retargetable scroll spring, independent of focus/OS auto-scroll. */
@android.annotation.SuppressLint("ViewConstructor") // Programmatic-only: motion policy comes from launcher settings.
final class WifiCarousel extends ScrollView {
    private final boolean smooth;
    private SpringAnimation motion;

    WifiCarousel(Context context, boolean smooth) {
        super(context);
        this.smooth = smooth;
        setFocusable(false);
        setSmoothScrollingEnabled(false);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        // Focus clearance belongs to the content, so the viewport can clip its exact rectangle.
        setClipToPadding(true);
        setClipChildren(true);
        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength(Math.round(8 * getResources().getDisplayMetrics().density));
    }

    void moveTo(int y, boolean animate) {
        if (!smooth || !animate || !isLaidOut()) {
            stop(); scrollTo(0, y);
            return;
        }
        if (getScrollY() == y && (motion == null || !motion.isRunning())) return;
        if (motion == null) {
            motion = new SpringAnimation(this, DynamicAnimation.SCROLL_Y);
            motion.setMinimumVisibleChange(1f);
            motion.setSpring(new SpringForce().setStiffness(1100f).setDampingRatio(1f));
        }
        motion.animateToFinalPosition(y);
    }

    void stop() { if (motion != null) motion.cancel(); }

    @Override protected void dispatchDraw(Canvas canvas) {
        // ScrollView's child is taller than this view. Explicitly clip its drawing, including
        // translated RenderNodes, rather than relying on an ancestor's clipChildren flag.
        int saved = canvas.save();
        canvas.clipRect(getScrollX(), getScrollY(), getScrollX() + getWidth(), getScrollY() + getHeight());
        super.dispatchDraw(canvas);
        canvas.restoreToCount(saved);
    }

    // The caller chooses whole-row destinations. Android must not jump to a newly focused row first.
    @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) { return 0; }
    @Override public boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate) { return false; }
    @Override public boolean executeKeyEvent(KeyEvent event) { return false; }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateRows();
    }

    @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        updateRows();
    }

    private void updateRows() {
        if (getChildCount() == 0) return;
        ViewGroup rows = (ViewGroup) getChildAt(0);
        int y = getScrollY();
        int top = y + rows.getPaddingTop();
        int bottom = y + getHeight() - rows.getPaddingBottom();
        // Moving a RenderNode does not redraw its sampled glass. Refresh just the visible few rows.
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            // Keep layout/focus available, but don't expose a neighbouring card in focus gutters.
            boolean visible = row.getBottom() > top && row.getTop() < bottom;
            float alpha = visible ? 1f : 0f;
            if (row.getAlpha() != alpha) row.setAlpha(alpha);
            if (visible)
                ControlBackdrop.restorePositions(row);
        }
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
