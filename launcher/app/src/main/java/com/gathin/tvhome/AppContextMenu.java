package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import java.util.ArrayList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A small, same-window menu. Only one tiny snapshot and one panel texture are used. */
final class AppContextMenu {
    static final class Action {
        final String title;
        final int icon;
        final boolean destructive;
        final Runnable run;
        final List<Action> children;
        Action(String title, int icon, boolean destructive, Runnable run) {
            this(title, icon, destructive, run, null);
        }
        Action(String title, int icon, boolean destructive, Runnable run, List<Action> children) {
            this.title = title; this.icon = icon; this.destructive = destructive;
            this.run = run; this.children = children;
        }
    }
    private static final ThreadPoolExecutor BLUR = new ThreadPoolExecutor(0, 1, 2, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1), task -> new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "PeachTileMenu"), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Activity host;
    private final FrameLayout stage, root;
    private final ViewGroup home;
    private final View anchor;
    private final LinearLayout panel;
    private final View shade;
    private final float density;
    private final boolean animate;
    private final Runnable dismissed, tick;
    private final android.view.ViewTreeObserver.OnGlobalFocusChangeListener focusListener;
    private final List<Action> main;
    private final int focusability, accessibility;
    private final RectF tile = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Bitmap backdrop;
    private final ArrayList<SpringAnimation> rowSprings = new ArrayList<>();
    private boolean revealed, subpage, showing;
    private volatile boolean closing;
    private float progress;
    private SpringAnimation spring;
    private Runnable afterClose;
    private final Runnable fallback = this::reveal;
    private static final FloatPropertyCompat<AppContextMenu> REVEAL = new FloatPropertyCompat<AppContextMenu>("tileMenu") {
        @Override public float getValue(AppContextMenu value) { return value.progress; }
        @Override public void setValue(AppContextMenu value, float amount) { value.apply(amount); }
    };

    AppContextMenu(Activity host, ViewGroup home, View anchor, List<Action> actions,
                   boolean animate, boolean reduced, Runnable tick, Runnable dismissed) {
        this.host = host; this.home = home; this.anchor = anchor; this.main = actions;
        this.animate = animate && !reduced; this.tick = tick; this.dismissed = dismissed;
        focusListener = (before, after) -> { if (before != null && after != null && after != before) this.tick.run(); };
        density = host.getResources().getDisplayMetrics().density;
        stage = host.findViewById(android.R.id.content);
        focusability = home.getDescendantFocusability(); accessibility = home.getImportantForAccessibility();
        root = new FrameLayout(host);
        root.setClipChildren(false); root.setClipToPadding(false);
        int[] origin = new int[2], at = new int[2];
        stage.getLocationOnScreen(origin); anchor.getLocationOnScreen(at);
        // getLocationOnScreen already includes the pivot/scale offset of the transformed origin.
        tile.set(at[0] - origin[0], at[1] - origin[1],
                at[0] - origin[0] + anchor.getWidth() * anchor.getScaleX(),
                at[1] - origin[1] + anchor.getHeight() * anchor.getScaleY());
        shade = new View(host) {
            private final Path hole = new Path(), shape = new Path();
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                hole.rewind(); hole.setFillType(Path.FillType.EVEN_ODD);
                hole.addRect(0, 0, w, h, Path.Direction.CW);
                ContinuousCorners.set(shape, tile.left, tile.top, tile.right, tile.bottom,
                        AppAdapter.cornerRadius(anchor.getWidth(), density) * anchor.getScaleX());
                hole.addPath(shape); paint.setColor(0x60000000);
            }
            @Override protected void onDraw(Canvas canvas) { canvas.drawPath(hole, paint); }
        };
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        shade.setOnClickListener(v -> close());
        panel = new LinearLayout(host); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(5), dp(5), dp(5), dp(5));
        panel.setClipChildren(false); panel.setClipToPadding(false);
        panel.setElevation(dp(5)); panel.setAlpha(0f); shade.setAlpha(0f);
        root.addView(panel, new FrameLayout.LayoutParams(Math.min(dp(228), stage.getWidth() - dp(32)), -2));
        populate(actions);
    }

    void show() {
        showing = true;
        anchor.setActivated(true);
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        home.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        boolean glass = host.getSharedPreferences(Config.SETTINGS_NAME, 0).getBoolean("liquid_glass", true);
        if (glass) {
            Bitmap capture = ControlBackdrop.snapshot(stage);
            int[] location = new int[2]; stage.getLocationInWindow(location);
            Rect source = new Rect(location[0], location[1], location[0] + stage.getWidth(), location[1] + stage.getHeight());
            try {
                PixelCopy.request(host.getWindow(), source, capture, result -> {
                    if (closing || revealed || result != PixelCopy.SUCCESS) { if (result != PixelCopy.SUCCESS) reveal(); return; }
                    BLUR.execute(() -> {
                        if (closing) return;
                        ControlBackdrop.blur(capture);
                        handler.post(() -> { if (!closing && !revealed) { backdrop = capture; reveal(); } });
                    });
                }, handler);
            } catch (IllegalArgumentException unavailableFrame) { handler.post(fallback); }
        }
        stage.addView(root, new FrameLayout.LayoutParams(-1, -1));
        root.getViewTreeObserver().addOnGlobalFocusChangeListener(focusListener);
        handler.postDelayed(fallback, glass ? 160 : 0);
    }

    private void reveal() {
        if (!showing || closing || revealed) return;
        revealed = true; handler.removeCallbacks(fallback);
        panel.setBackground(new ControlBackdrop(panel, backdrop, density, dp(21),
                backdrop == null ? 1f : stage.getWidth() / (float) backdrop.getWidth(), 0x88211d25, false, true));
        layout();
        panel.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        spring = new SpringAnimation(this, REVEAL);
        spring.setSpring(new SpringForce(1f).setStiffness(1000f).setDampingRatio(.8f));
        spring.setMinimumVisibleChange(.001f);
        spring.addEndListener((animation, canceled, value, velocity) -> {
            if (!canceled) { panel.setLayerType(View.LAYER_TYPE_NONE, null); if (closing) finish(); }
        });
        if (animate) spring.start(); else apply(1f);
        panel.getChildAt(0).requestFocus();
    }

    private void layout() {
        int width = panel.getLayoutParams().width;
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(stage.getHeight() - dp(32), View.MeasureSpec.AT_MOST));
        MenuPlacement at = MenuPlacement.beside(stage.getWidth(), stage.getHeight(),
                Math.round(tile.left), Math.round(tile.top), Math.round(tile.right), Math.round(tile.bottom),
                width, panel.getMeasuredHeight(), dp(8), dp(16), home.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        lp.leftMargin = at.left; lp.topMargin = at.top; lp.gravity = Gravity.TOP | Gravity.LEFT;
        panel.setLayoutParams(lp);
        panel.setPivotX(at.right ? 0 : width);
        panel.setPivotY(Math.max(0, Math.min(panel.getMeasuredHeight(), tile.centerY() - at.top)));
    }

    private void populate(List<Action> actions) {
        for (SpringAnimation animation : rowSprings) animation.cancel();
        rowSprings.clear();
        panel.removeAllViews();
        for (Action action : actions) {
            LinearLayout row = new LinearLayout(host); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), 0, dp(8), 0); row.setFocusable(true); row.setClickable(true);
            GradientDrawable selected = new GradientDrawable(); selected.setColor(0xfaffffff); selected.setCornerRadius(dp(15));
            StateListDrawable background = new StateListDrawable();
            background.addState(new int[]{android.R.attr.state_focused}, selected);
            background.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
            row.setBackground(background);
            int idle = action.destructive ? 0xfff05a62 : 0xe6ffffff;
            ImageView icon = new ImageView(host);
            if (action.icon != 0) { icon.setImageResource(action.icon); row.addView(icon, new LinearLayout.LayoutParams(dp(14), dp(14))); }
            TextView title = new TextView(host); title.setText(action.title); title.setTextSize(11);
            title.setTypeface(host.getResources().getFont(R.font.inter_medium)); title.setIncludeFontPadding(false);
            title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, -2, 1); textLp.setMarginStart(dp(action.icon == 0 ? 16 : 6));
            row.addView(title, textLp);
            TextView arrow = new TextView(host); arrow.setText(action.children == null ? "" : "›"); arrow.setTextSize(17);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(12), -2));
            title.setTextColor(idle); arrow.setTextColor(idle); icon.setImageTintList(android.content.res.ColorStateList.valueOf(idle));
            SpringAnimation zoom = new SpringAnimation(row, SpringAnimation.SCALE_X, 1f);
            rowSprings.add(zoom);
            zoom.setMinimumVisibleChange(.001f);
            zoom.addUpdateListener((animation, value, velocity) -> row.setScaleY(value));
            row.setOnFocusChangeListener((v, focus) -> {
                int color = focus ? action.destructive ? 0xffb5202c : 0xff151515 : idle;
                title.setTextColor(color); arrow.setTextColor(color); icon.setImageTintList(android.content.res.ColorStateList.valueOf(color));
                if (animate) { zoom.setSpring(new SpringForce(focus ? 1.015f : 1f).setStiffness(1500f).setDampingRatio(.75f)); zoom.animateToFinalPosition(focus ? 1.015f : 1f); }
                else { row.setScaleX(1f); row.setScaleY(1f); }
            });
            Runnable activate = () -> {
                if (closing) return;
                if (action.children != null) { subpage = true; populate(action.children); layout(); panel.getChildAt(0).requestFocus(); }
                else closeThen(action.run);
            };
            row.setOnClickListener(v -> activate.run());
            row.setOnKeyListener(new View.OnKeyListener() {
                long down = -1;
                @Override public boolean onKey(View v, int code, KeyEvent event) {
                    if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_NUMPAD_ENTER) return false;
                    if (event.getAction() == KeyEvent.ACTION_DOWN) { if (event.getRepeatCount() == 0) down = event.getDownTime(); }
                    else if (event.getAction() == KeyEvent.ACTION_UP) {
                        boolean run = down == event.getDownTime() && !event.isCanceled(); down = -1;
                        if (run) activate.run();
                    }
                    return true;
                }
            });
            panel.addView(row, new LinearLayout.LayoutParams(-1, dp(28)));
        }
    }

    boolean dispatch(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                if (subpage) { subpage = false; populate(main); layout(); panel.getChildAt(0).requestFocus(); }
                else close();
            }
            return true;
        }
        if (root.dispatchKeyEvent(event)) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
            View focus = root.findFocus();
            View next = focus == null ? panel.getChildAt(0) : focus.focusSearch(code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN);
            if (next != null && next != anchor) next.requestFocus();
        }
        return true;
    }
    private void apply(float value) {
        progress = value; float alpha = Math.max(0f, Math.min(1f, value));
        panel.setAlpha(alpha); shade.setAlpha(alpha);
        panel.setScaleX(.88f + .12f * value); panel.setScaleY(.88f + .12f * value);
    }
    void close() { closeThen(null); }
    void closeThen(Runnable action) {
        if (closing) return;
        closing = true; afterClose = action; handler.removeCallbacks(fallback);
        if (!revealed || !animate) { finish(); return; }
        panel.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        spring.getSpring().setStiffness(1200f).setDampingRatio(1f); spring.animateToFinalPosition(0f);
    }
    void dismissImmediately() { closing = true; afterClose = null; finish(); }
    private void finish() {
        if (!showing) return;
        showing = false; closing = true; handler.removeCallbacks(fallback);
        for (SpringAnimation animation : rowSprings) animation.cancel();
        rowSprings.clear();
        if (spring != null) spring.cancel(); panel.setLayerType(View.LAYER_TYPE_NONE, null);
        root.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusListener);
        home.setDescendantFocusability(focusability);
        // Transfer directly while the menu still owns focus: removing it first lets
        // ViewRoot focus the first dock tile before the explicit anchor request.
        if (anchor.isAttachedToWindow() && home.hasWindowFocus()) anchor.requestFocus();
        anchor.setActivated(false);
        stage.removeView(root);
        home.setImportantForAccessibility(accessibility);
        dismissed.run();
        Runnable action = afterClose; afterClose = null; if (action != null) action.run();
    }
    private int dp(float value) { return Math.round(value * density); }
}
