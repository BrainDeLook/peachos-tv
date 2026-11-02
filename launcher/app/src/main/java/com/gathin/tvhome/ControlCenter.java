package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.Context;
import android.view.Window;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.text.TextUtils;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.core.graphics.ColorUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compact controls with one shared frosted backdrop and a reversible capsule-to-header transition. */
final class ControlCenter {
    interface Actions {
        void settingsGearHeld();
        boolean remoteSettingsHoldEnabled();
        void controlCenterDismissed(ControlCenter center, boolean returnToGear);
        void controlInputRows(LinearLayout rows, java.util.function.Consumer<Runnable> activate);
        void controlOpen(Intent intent);
        boolean controlBackdropReady();
        String controlBackdropKey();
        void controlSleep();
    }
    private final Context host;
    private final Actions actions;
    private final Window captureWindow;
    private final boolean floating;
    private final boolean transparentMaterial;
    private final FrameLayout stage;
    private final ViewGroup home;
    private int homeFocusability, homeAccessibility;
    private View homeFocus;
    private boolean showing;
    private final View anchor;
    private final View capsule;
    private final TextClock sourceClock;
    private final TextClock clock;
    private final FrameLayout root;
    private final View scrim;
    private final LinearLayout panel, tabs;
    private final FrameLayout body;
    private final ScrollView pageScroll;
    private static final class Page {
        final LinearLayout content;
        boolean interactive;
        boolean layerRequested;
        int networkRevision = -1;
        TextView overviewNetwork, networkStatus, vpnStatus;
        ImageView overviewNetworkIcon, networkStatusIcon;
        LinearLayout wifiArea, wifiRows;
        TextView wifiNote;
        View wifiAction;
        final Map<String, WifiRow> wifiEntries = new LinkedHashMap<>();
        final List<String> wifiOrder = new ArrayList<>();
        final WifiViewport wifiViewport = new WifiViewport();
        Page(Context context) {
            content = new LinearLayout(context) {
                @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                    return interactive && super.dispatchTouchEvent(event);
                }
            };
            content.setOrientation(LinearLayout.VERTICAL);
            content.setClipChildren(false);
            content.setClipToPadding(false);
        }
    }
    // Reuse the small, static pages during this opening. Input rows are rebuilt because their
    // connection state can change while the panel is open.
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private Page currentPage;
    private View outgoingPage;
    private boolean pagePositionsDirty;
    private boolean bodyLayerRequested;
    private final float density;
    private final int maxBodyHeight;
    private final int wifiBodyHeight;
    private static final long MAX_LAYER_BYTES = 2L * 1024 * 1024;
    private final boolean animate, reduced;
    private final Typeface regular, medium;
    private final Bitmap backdrop;
    private final boolean captureNeeded;
    private final String backdropKey;
    private static final class PreparedBackdrop {
        final Bitmap bitmap;
        final long at = android.os.SystemClock.uptimeMillis();
        PreparedBackdrop(Bitmap bitmap) { this.bitmap = bitmap; }
    }
    // Six 160-pixel images occupy about 0.33 MiB at 16:9; no full-size frame is retained.
    private static final android.util.LruCache<String, PreparedBackdrop> HOME_GLASS = new android.util.LruCache<>(6);
    private final float backdropScale;
    private final List<View> tabViews = new ArrayList<>();
    private final Map<String, Intent> vpnApps = new LinkedHashMap<>();
    private SpringAnimation spring;
    private float reveal, clockDx, clockDy, gearDx, gearDy, gearScale;
    private View overviewTab;
    private boolean revealed;
    private volatile boolean closing;
    private boolean returnToGear;
    private Runnable afterClose;
    private Runnable captureFrame, revealFrame;
    private ViewTreeObserver.OnPreDrawListener revealPreDraw;
    private final ViewTreeObserver.OnPreDrawListener pagePositionListener;
    private int captureAttempts;
    private static final java.util.concurrent.Executor GLASS_WORK = new java.util.concurrent.ThreadPoolExecutor(
            0, 1, 2, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(1),
            task -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "PeachGlass"), new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    private String selected = "";
    private View selectedTab;
    private AirPlayControl airPlay;
    private final ConnectivityManager connectivity;
    private String networkTransport;
    private boolean networkOnline, networkEthernet, vpnConnected;
    private int networkRevision;
    private ImageView networkTab;
    private int networkTabIcon;
    private WifiScanner wifi;
    private WifiScanner.Snapshot pendingWifi;
    private final Runnable renderWifi = this::renderWifiNetworks;

    private static final FloatPropertyCompat<ControlCenter> REVEAL = new FloatPropertyCompat<ControlCenter>("controlReveal") {
        @Override public float getValue(ControlCenter center) { return center.reveal; }
        @Override public void setValue(ControlCenter center, float value) { center.applyReveal(value); }
    };

    ControlCenter(MainActivity host, View anchor, boolean inputs, boolean animate, boolean reduced, String vpnApp) {
        this(host, host, host.findViewById(android.R.id.content), host.findViewById(R.id.root),
                anchor, host.findViewById(R.id.topButtons), host.findViewById(R.id.compactClock),
                host.getWindow(), null, inputs, animate, reduced, vpnApp);
    }

    ControlCenter(Context host, Actions actions, FrameLayout stage, ViewGroup home, View anchor,
                  View capsule, TextClock sourceClock, Window captureWindow, Bitmap captured,
                  boolean inputs, boolean animate, boolean reduced, String vpnApp) {
        this(host, actions, stage, home, anchor, capsule, sourceClock, captureWindow, captured,
                inputs, animate, reduced, vpnApp, null);
    }

    ControlCenter(Context host, Actions actions, FrameLayout stage, ViewGroup home, View anchor,
                  View capsule, TextClock sourceClock, Window captureWindow, Bitmap captured,
                  boolean inputs, boolean animate, boolean reduced, String vpnApp, Map<String, Intent> preparedVpnApps) {
        this.host = host; this.actions = actions; this.stage = stage; this.home = home;
        this.anchor = anchor; this.capsule = capsule; this.sourceClock = sourceClock;
        this.captureWindow = captureWindow; floating = captureWindow == null;
        this.animate = animate; this.reduced = reduced;
        density = host.getResources().getDisplayMetrics().density;
        boolean glass = host.getSharedPreferences(Config.SETTINGS_NAME, 0).getBoolean("liquid_glass", true);
        // Home keeps its original sampled material; transparency belongs only to the in-app window.
        transparentMaterial = floating && glass;
        backdropKey = floating ? "" : actions.controlBackdropKey();
        PreparedBackdrop cached = floating ? null : HOME_GLASS.get(backdropKey);
        boolean fresh = cached != null && android.os.SystemClock.uptimeMillis() - cached.at < 60_000;
        backdrop = !glass ? null : floating ? captured : fresh ? cached.bitmap : ControlBackdrop.snapshot(stage);
        captureNeeded = glass && !floating && !fresh;
        int width = stage.getWidth() > 0 ? stage.getWidth() : host.getResources().getDisplayMetrics().widthPixels;
        backdropScale = backdrop == null ? 1f : width / (float) backdrop.getWidth();
        regular = host.getResources().getFont(R.font.inter_regular);
        medium = host.getResources().getFont(R.font.inter_medium);
        connectivity = host.getSystemService(ConnectivityManager.class);
        refreshNetwork();
        vpnApps.putAll(preparedVpnApps == null ? discoverVpnApps(host, vpnApp) : preparedVpnApps);

        root = new FrameLayout(host) {
            @Override public View focusSearch(View focused, int direction) {
                View next = super.focusSearch(focused, direction);
                if (direction == View.FOCUS_UP && body.hasFocus()) {
                    View candidate = next;
                    while (candidate != null && candidate != body) {
                        android.view.ViewParent parent = candidate.getParent();
                        candidate = parent instanceof View ? (View) parent : null;
                    }
                    // Leaving the page upwards returns to its own tab, regardless of button width.
                    if (candidate != body) return selectedTab;
                }
                return next;
            }
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                    if (event.getAction() == KeyEvent.ACTION_UP) close();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        scrim = new View(host); scrim.setBackgroundColor(floating ? 0x66000000 : 0x50000000);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        root.setOnClickListener(v -> close());
        root.setFocusable(false);
        panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false); panel.setClipToPadding(false);
        panel.setOnClickListener(v -> { });
        panel.setFocusable(false);
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(dp(244), -2, Gravity.TOP | Gravity.END);
        panelLp.topMargin = dp(16); panelLp.setMarginEnd(dp(22));
        root.addView(panel, panelLp);

        LinearLayout header = new LinearLayout(host);
        header.setClipChildren(false); header.setClipToPadding(false);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, dp(2), 0);
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(30)));
        clock = new TextClock(host);
        clock.setFormat12Hour("h:mm"); clock.setFormat24Hour("HH:mm");
        // Both sides of the capsule handoff use the same glyph metrics and colour.
        clock.setFontFeatureSettings(sourceClock.getFontFeatureSettings());
        clock.setTypeface(sourceClock.getTypeface());
        clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sourceClock.getTextSize());
        clock.setTextColor(sourceClock.getTextColors());
        clock.setLetterSpacing(sourceClock.getLetterSpacing());
        clock.setIncludeFontPadding(false);
        clock.setGravity(Gravity.CENTER);
        clock.setPadding(dp(9), 0, dp(9), 0);
        clock.setBackgroundResource(R.drawable.compact_clock_bg);
        header.addView(clock, new LinearLayout.LayoutParams(-2, dp(28)));
        header.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
        tabs = new LinearLayout(host); tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setClipChildren(false); tabs.setClipToPadding(false);
        header.addView(tabs);
        if (vpnConnected || !vpnApps.isEmpty()) addTab("vpn", R.drawable.cc_vpn, R.string.cc_vpn);
        addTab("overview", R.drawable.ic_settings, R.string.cc_title);
        overviewTab = tabViews.get(tabViews.size() - 1);
        overviewTab.setOnLongClickListener(v -> {
            actions.settingsGearHeld();
            return true;
        });
        if (inputs) addTab("inputs", R.drawable.ic_input, R.string.inputs);
        networkTabIcon = networkEthernet ? R.drawable.cc_ethernet : R.drawable.ic_wifi;
        addTab("network", networkTabIcon, R.string.quick_network);
        networkTab = (ImageView) tabViews.get(tabViews.size() - 1);

        maxBodyHeight = Math.min(dp(320), host.getResources().getDisplayMetrics().heightPixels - dp(96));
        wifiBodyHeight = Math.min(dp(420), host.getResources().getDisplayMetrics().heightPixels - dp(96));
        pageScroll = new ScrollView(host) {
            @Override protected void onMeasure(int w, int h) {
                int limit = currentPage != null && currentPage.wifiArea != null && !networkEthernet
                        ? wifiBodyHeight : maxBodyHeight;
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
            }
        };
        ScrollView scroll = pageScroll;
        scroll.setPadding(dp(4), dp(4), dp(4), dp(4));
        scroll.setClipChildren(false); scroll.setClipToPadding(false);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false); scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, -2);
        scrollLp.topMargin = dp(8); panel.addView(scroll, scrollLp);
        body = new FrameLayout(host) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                // Invisible cached pages retain their RenderNodes, but their old height must not
                // stretch the selected page or keep the scroll range of an inactive page.
                int height = currentPage == null ? 0 : currentPage.content.getMeasuredHeight();
                if (outgoingPage != null) height = Math.max(height, outgoingPage.getMeasuredHeight());
                height = Math.max(getMinimumHeight(), height + getPaddingTop() + getPaddingBottom());
                setMeasuredDimension(getMeasuredWidthAndState(), resolveSizeAndState(height, heightMeasureSpec, 0));
                for (Page page : pages.values()) updateAnimationLayer(page.content, page.layerRequested);
                updateAnimationLayer(this, bodyLayerRequested);
            }
        };
        body.setClipChildren(false); scroll.addView(body);
        pagePositionListener = () -> {
            if (pagePositionsDirty && currentPage != null) {
                // Sample after ScrollView has clamped its old scroll offset to the new page.
                ControlBackdrop.restorePositions(currentPage.content);
                pagePositionsDirty = false;
            }
            return true;
        };
        root.getViewTreeObserver().addOnPreDrawListener(pagePositionListener);
        select("overview");
    }

    boolean isShowing() { return showing; }

    static Map<String, Intent> discoverVpnApps(Context context, String configuredApp) {
        Map<String, Intent> result = new LinkedHashMap<>();
        PackageManager pm = context.getPackageManager();
        // Installed clients are not presented as saved profiles: only the owning app can read those.
        for (ResolveInfo info : pm.queryIntentServices(new Intent(VpnService.SERVICE_INTERFACE), 0)) {
            String pkg = info.serviceInfo.packageName;
            if (!"android.permission.BIND_VPN_SERVICE".equals(info.serviceInfo.permission)) continue;
            Intent target = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (target == null) target = pm.getLaunchIntentForPackage(pkg);
            if (target != null) result.put(String.valueOf(info.serviceInfo.applicationInfo.loadLabel(pm)), target);
        }
        if (!configuredApp.isEmpty()) {
            Intent target = pm.getLeanbackLaunchIntentForPackage(configuredApp);
            if (target == null) target = pm.getLaunchIntentForPackage(configuredApp);
            if (target != null) result.put(context.getString(R.string.cc_vpn_app), target);
        }
        return result;
    }

    static void trimBackdropCache() {
        HOME_GLASS.evictAll();
        ControlBackdrop.trimLightingCache();
    }

    boolean dispatchKeyEvent(KeyEvent event) { return root.dispatchKeyEvent(event); }

    void show() {
        long showStarted = android.os.SystemClock.uptimeMillis();
        panel.setAlpha(0f); scrim.setAlpha(0f);
        revealFrame = () -> {
            if (!showing || closing) return;
            ControlBackdrop.backdropPrepared(panel);
            int[] from = new int[2], to = new int[2];
            sourceClock.getLocationOnScreen(from); clock.getLocationOnScreen(to);
            clockDx = from[0] + sourceClock.getWidth() * .5f - to[0] - clock.getWidth() * .5f;
            clockDy = from[1] + sourceClock.getHeight() * .5f - to[1] - clock.getHeight() * .5f;
            anchor.getLocationOnScreen(from); overviewTab.getLocationOnScreen(to);
            gearDx = from[0] + anchor.getWidth() * anchor.getScaleX() * .5f - to[0] - overviewTab.getWidth() * .5f;
            gearDy = from[1] + anchor.getHeight() * anchor.getScaleY() * .5f - to[1] - overviewTab.getHeight() * .5f;
            gearScale = anchor.getWidth() * anchor.getScaleX() / overviewTab.getWidth();
            if (floating) { clockDx = clockDy = gearDx = gearDy = 0f; gearScale = 1f; }
            body.setPivotX(body.getWidth() * .5f); body.setPivotY(body.getHeight() * .5f);
            if (animate && !reduced) {
                // Build the static controls once before the spring, rather than rasterizing all their
                // clipped glass paths during every alpha/scale frame. Released as soon as motion ends.
                bodyLayerRequested = true;
                updateAnimationLayer(body, true);
                if (body.getLayerType() == View.LAYER_TYPE_HARDWARE) body.buildLayer();
            }
            revealed = true;
            applyReveal(animate ? 0f : 1f);
            panel.setAlpha(1f);
            if (animate && !reduced) {
                spring = new SpringAnimation(this, REVEAL);
                spring.setSpring(new SpringForce(1f).setStiffness(700f).setDampingRatio(.78f));
                spring.setMinimumVisibleChange(.001f);
                spring.addEndListener((animation, canceled, value, velocity) -> {
                    if (!canceled) {
                        bodyLayerRequested = false;
                        updateAnimationLayer(body, false);
                        if (closing) finishDismiss();
                    }
                });
                spring.start();
            } else if (animate) {
                // Reduced motion keeps only the short opacity transition, without separation or zoom.
                panel.setAlpha(0f); applyReveal(1f);
                panel.animate().alpha(1f).setDuration(100).start();
            }
            overviewTab.requestFocusFromTouch();
            if (floating) android.util.Log.d("PeachOverlay", "Reveal prepared "
                    + (android.os.SystemClock.uptimeMillis() - showStarted) + "ms");
        };
        if (backdrop != null && captureNeeded) {
            captureFrame = new Runnable() {
                @Override public void run() {
                    if (!showing || closing) return;
                    // Capture what Home currently displays; network poster loading must not
                    // delay the controls. Only fully settled snapshots enter the reusable cache.
                    int[] location = new int[2]; stage.getLocationInWindow(location);
                    Rect source = new Rect(location[0], location[1],
                            location[0] + stage.getWidth(), location[1] + stage.getHeight());
                    captureAttempts++;
                    try {
                        PixelCopy.request(captureWindow, source, backdrop, result -> {
                            if (closing) return;
                            android.util.Log.d("PeachControls", "Window copy " + result + " attempt " + captureAttempts);
                            if (result != PixelCopy.SUCCESS) {
                                if (captureAttempts < 3) stage.postOnAnimation(captureFrame);
                                else panel.post(revealFrame);
                                return;
                            }
                            GLASS_WORK.execute(() -> {
                                if (closing) return;
                                ControlBackdrop.blur(backdrop);
                                panel.post(() -> {
                                    if (!closing) {
                                        if (actions.controlBackdropReady()) HOME_GLASS.put(backdropKey, new PreparedBackdrop(backdrop));
                                        revealFrame.run();
                                    }
                                });
                            });
                        }, new Handler(Looper.getMainLooper()));
                    } catch (IllegalArgumentException noWindowFrame) {
                        if (captureAttempts < 3) stage.postOnAnimation(captureFrame);
                        else panel.post(revealFrame);
                    }
                }
            };
        }
        // A sibling of Home in the same window: removing the panel and restoring the capsule now
        // produce one render transaction, rather than racing two independently presented surfaces.
        homeFocusability = home.getDescendantFocusability();
        homeAccessibility = home.getImportantForAccessibility();
        homeFocus = home.findFocus();
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        home.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        showing = true;
        stage.addView(root, new FrameLayout.LayoutParams(-1, -1));
        if (floating) {
            // The temporary window and controls join the same first traversal. Prepare the spring
            // after layout, without posting a second traversal behind an otherwise empty frame.
            revealPreDraw = new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                    revealPreDraw = null;
                    revealFrame.run();
                    return true;
                }
            };
            root.getViewTreeObserver().addOnPreDrawListener(revealPreDraw);
        } else if (backdrop == null || !captureNeeded) panel.post(revealFrame);
        else stage.postOnAnimation(() -> {
            // Home resumed from another app has not necessarily submitted a readable frame yet.
            // Wait for its first presentation rather than retaining the matte bitmap for this whole opening.
            if (!closing) stage.postOnAnimation(captureFrame);
        });
    }

    void close() { closeThen(null); }

    private void applyReveal(float value) {
        reveal = value;
        float visible = Math.max(0f, Math.min(1f, value));
        float headerAlpha = Math.min(1f, visible * 4f);
        capsule.setAlpha(1f - headerAlpha);
        // Dissolve the separate clock material before it joins the original capsule.
        // Keep a single set of glyphs while the header moves. Even the last 4% of travel can span
        // several pixels: crossfading there shows two different clock positions during closing.
        clock.getBackground().setAlpha(Math.round(headerAlpha * 255));
        // The moving header keeps the glyphs through its final frame. finishDismiss restores the
        // original and removes this overlay before the same window's next draw.
        float handoff = closing || visible > 0f ? 1f : 0f;
        clock.setAlpha(floating ? headerAlpha : handoff); overviewTab.setAlpha(floating ? headerAlpha : handoff);
        sourceClock.setAlpha(1f - handoff); anchor.setAlpha(1f - handoff);
        clock.setTranslationX(clockDx * (1f - value)); clock.setTranslationY(clockDy * (1f - value));
        overviewTab.setTranslationX(gearDx * (1f - value)); overviewTab.setTranslationY(gearDy * (1f - value));
        float scale = gearScale + (1f - gearScale) * value;
        overviewTab.setScaleX(scale); overviewTab.setScaleY(scale);
        float blocks = Math.max(0f, Math.min(1f, (visible - .12f) / .88f));
        for (View tab : tabViews) if (tab != overviewTab) tab.setAlpha(blocks);
        body.setAlpha(blocks);
        body.setScaleX(.78f + .22f * value); body.setScaleY(.78f + .22f * value);
        scrim.setAlpha(visible);
    }

    void closeThen(Runnable action) {
        if (closing) {
            if (action != null) { afterClose = action; returnToGear = false; }
            return;
        }
        closing = true; returnToGear = action == null; afterClose = action;
        stopWifi();
        if (returnToGear && !actions.remoteSettingsHoldEnabled()) {
            overviewTab.getBackground().setState(new int[]{android.R.attr.state_focused});
        }
        if (!revealed || !animate) { finishDismiss(); return; }
        if (reduced) {
            panel.animate().cancel();
            clock.setAlpha(0f); overviewTab.setAlpha(0f);
            sourceClock.setAlpha(1f); anchor.setAlpha(1f);
            capsule.setAlpha(0f);
            capsule.animate().alpha(1f).setDuration(100).start();
            panel.animate().alpha(0f).setDuration(100).withEndAction(this::finishDismiss).start();
            return;
        }
        // Retarget the running spring: Back during opening preserves both position and velocity.
        bodyLayerRequested = true;
        updateAnimationLayer(body, true);
        spring.getSpring().setStiffness(900f).setDampingRatio(1f);
        spring.animateToFinalPosition(0f);
    }

    private void finishDismiss() {
        if (!showing) return;
        showing = false;
        closing = true;
        stopWifi();
        if (airPlay != null) { airPlay.close(); airPlay = null; }
        if (captureFrame != null) stage.removeCallbacks(captureFrame);
        if (revealFrame != null) panel.removeCallbacks(revealFrame);
        if (revealPreDraw != null) {
            root.getViewTreeObserver().removeOnPreDrawListener(revealPreDraw);
            revealPreDraw = null;
        }
        root.getViewTreeObserver().removeOnPreDrawListener(pagePositionListener);
        if (spring != null) spring.cancel();
        bodyLayerRequested = false;
        updateAnimationLayer(body, false);
        capsule.animate().cancel();
        clearOutgoingPage();
        for (Page page : pages.values()) {
            ControlBackdrop.finishAnimations(page.content);
            page.content.setLayerType(View.LAYER_TYPE_NONE, null);
        }
        for (View tab : tabViews) tab.getBackground().jumpToCurrentState();
        home.setDescendantFocusability(homeFocusability);
        home.setImportantForAccessibility(homeAccessibility);
        sourceClock.setAlpha(1f); anchor.setAlpha(1f);
        capsule.setAlpha(1f); capsule.setVisibility(View.VISIBLE);
        if (!returnToGear && afterClose == null && homeFocus != null && home.hasWindowFocus()) homeFocus.requestFocusFromTouch();
        actions.controlCenterDismissed(this, returnToGear);
        stage.removeView(root);
        Runnable action = afterClose; afterClose = null;
        if (action != null) action.run();
    }

    void dismissImmediately() {
        returnToGear = false; afterClose = null;
        closing = true;
        if (spring != null) spring.cancel();
        panel.animate().cancel();
        finishDismiss();
    }

    private void addTab(String key, int icon, int title) {
        ImageView tab = new ImageView(host);
        tab.setId(View.generateViewId()); tab.setImageResource(icon);
        tab.setPadding(dp(6), dp(6), dp(6), dp(6));
        tab.setContentDescription(host.getString(title)); tab.setFocusable(true); tab.setClickable(true);
        tab.setBackground(new TabStyle(tab, animate && !reduced)); tab.setTag(key);
        tabs.addView(tab, new LinearLayout.LayoutParams(dp(28), dp(28))); tabViews.add(tab);
        tab.setOnFocusChangeListener((v, focus) -> { if (focus) select(key); });
        tab.setOnClickListener(v -> {
            select(key);
            View first = currentPage.content.findViewWithTag("firstControl");
            if ("network".equals(key) && !networkEthernet) first = currentPage.wifiRows.getChildCount() > 0
                    ? currentPage.wifiRows.getChildAt(0) : currentPage.wifiAction;
            if (first != null) first.requestFocus();
        });
    }

    /** One reusable color animator per tab; interrupted transitions continue from the displayed colors. */
    private static final class TabStyle extends GradientDrawable {
        private final ImageView tab;
        private Drawable icon;
        private final boolean smooth;
        private final ValueAnimator transition = ValueAnimator.ofFloat(0f, 1f);
        private int backgroundColor, iconColor = 0xbfffffff;
        private int fromBackground, toBackground, fromIcon = 0xbfffffff, toIcon = 0xbfffffff;

        TabStyle(ImageView tab, boolean smooth) {
            this.tab = tab; this.smooth = smooth;
            icon = tab.getDrawable().mutate(); icon.setTint(iconColor);
            setShape(OVAL); setColor(Color.TRANSPARENT);
            transition.setDuration(140);
            transition.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, .2f, 1f));
            transition.addUpdateListener(animation -> blend(animation.getAnimatedFraction()));
        }

        void setIcon(int resource) {
            tab.setImageResource(resource);
            icon = tab.getDrawable().mutate();
            icon.setTint(iconColor);
        }

        @Override public boolean isStateful() { return true; }

        @Override protected boolean onStateChange(int[] states) {
            boolean focused = false, selected = false;
            for (int state : states) {
                if (state == android.R.attr.state_focused) focused = true;
                if (state == android.R.attr.state_selected) selected = true;
            }
            int background = focused ? 0xf5ffffff : selected ? 0x35ffffff : 0x00ffffff;
            int foreground = focused ? 0xff111111 : selected ? 0xe6ffffff : 0xbfffffff;
            if (background == toBackground && foreground == toIcon) return false;
            transition.cancel();
            fromBackground = backgroundColor; fromIcon = iconColor;
            toBackground = background; toIcon = foreground;
            if (smooth && tab.isLaidOut() && tab.isAttachedToWindow()) transition.start();
            else blend(1f);
            return true;
        }

        private void blend(float fraction) {
            backgroundColor = ColorUtils.blendARGB(fromBackground, toBackground, fraction);
            iconColor = ColorUtils.blendARGB(fromIcon, toIcon, fraction);
            setColor(backgroundColor); icon.setTint(iconColor);
        }

        @Override public void jumpToCurrentState() {
            transition.cancel(); blend(1f);
        }
    }

    private void select(String page) {
        if (page.equals(selected)) return;
        boolean clipWifi = "network".equals(page) || currentPage != null && currentPage.wifiArea != null;
        selected = page;
        if (!"network".equals(page)) stopWifi();
        for (View tab : tabViews) {
            boolean active = page.equals(tab.getTag());
            tab.setSelected(active);
            if (active) selectedTab = tab;
        }
        clearOutgoingPage();
        LinearLayout previous = currentPage == null ? null : currentPage.content;
        // A long AP list stays inside its viewport; the existing inner padding covers focus overshoot.
        pageScroll.setClipChildren(clipWifi);
        boolean crossfade = previous != null && previous.isLaidOut() && revealed && animate && !reduced && !closing;
        // A shorter destination must not clip the fading bottom rows of the previous page.
        if (crossfade) {
            body.setMinimumHeight(body.getHeight());
            ControlBackdrop.freezePositions(previous);
        }
        if (previous != null) {
            previous.animate().cancel();
            currentPage.interactive = false;
            previous.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            previous.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if (!crossfade) previous.setVisibility(View.INVISIBLE);
        }
        Page next = "inputs".equals(page) ? null : pages.get(page);
        boolean populate = next == null;
        if (populate) next = new Page(host);
        currentPage = next;
        LinearLayout column = next.content;
        column.animate().cancel();
        column.setAlpha(1f);
        column.setVisibility(View.VISIBLE);
        column.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        column.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        next.interactive = true;
        if (populate) {
            // Hardware page textures clip to their own bounds even with clipChildren=false.
            // Reserve room inside the texture for the focused button's scale and spring overshoot.
            column.setPadding(dp(5), dp(5), dp(5), dp(5));
            body.addView(column, new FrameLayout.LayoutParams(-1, -2));
            populatePage(page, column);
            Page obsolete = pages.put(page, next);
            if (obsolete != null) {
                ControlBackdrop.finishAnimations(obsolete.content);
                obsolete.content.setLayerType(View.LAYER_TYPE_NONE, null);
                body.removeView(obsolete.content);
            }
        }
        // Retain at most two small page textures. Repeated A/B switches no longer detach the
        // pages or synchronously build and destroy both layers at every alpha transition.
        for (Page cached : pages.values()) {
            cached.layerRequested = animate && !reduced && (cached.content == column || cached.content == previous);
            updateAnimationLayer(cached.content, cached.layerRequested);
        }
        pagePositionsDirty = true;
        body.requestLayout();
        applyNetworkStatus();
        syncWifi();
        if (!crossfade) { pageScroll.setClipChildren("network".equals(page)); return; }
        // A visual-only overlay cannot receive focus, clicks or accessibility traversal.
        // Keep at most the previous and current pages, even when keys repeat during a transition.
        outgoingPage = previous;
        // Match the former visual overlay order; the outgoing page rejects all touch and focus.
        previous.bringToFront();
        previous.animate().alpha(0f).setDuration(140)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (outgoingPage == previous) {
                        ControlBackdrop.finishAnimations(previous);
                        previous.setVisibility(View.INVISIBLE);
                        outgoingPage = null;
                        body.setMinimumHeight(0);
                        pageScroll.setClipChildren("network".equals(selected));
                    }
                }).start();
        column.setAlpha(0f);
        column.animate().alpha(1f).setDuration(140).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void clearOutgoingPage() {
        if (outgoingPage == null) return;
        outgoingPage.animate().cancel();
        ControlBackdrop.finishAnimations(outgoingPage);
        outgoingPage.setVisibility(View.INVISIBLE);
        outgoingPage = null;
        body.setMinimumHeight(0);
    }

    /** Bound retained GPU surfaces to the visible page height and two MiB of RGBA pixels. */
    private void updateAnimationLayer(View view, boolean requested) {
        int width = view.getMeasuredWidth(), height = view.getMeasuredHeight();
        boolean fits = width > 0 && height > 0 && height <= wifiBodyHeight
                && (long) width * height * 4 <= MAX_LAYER_BYTES;
        int type = requested && fits ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        if (view.getLayerType() != type) view.setLayerType(type, null);
    }

    private void populatePage(String page, LinearLayout column) {
        if ("inputs".equals(page)) {
            actions.controlInputRows(column, this::closeThen);
            for (int i = 0; i < column.getChildCount(); i++) {
                View row = column.getChildAt(i);
                if (row.findViewById(R.id.title) == null) continue;
                row.getLayoutParams().height = dp(44);
                row.setBackground(material(row, 23));
            }
            if (column.getChildCount() > 0) column.getChildAt(0).setTag("firstControl");
            return;
        }
        if ("network".equals(page)) {
            currentPage.networkStatus = info(column, R.drawable.ic_wifi, R.string.quick_network);
            currentPage.networkStatusIcon = (ImageView) ((LinearLayout) currentPage.networkStatus.getParent()).getChildAt(0);
            currentPage.wifiArea = column();
            currentPage.wifiNote = text(host.getString(R.string.cc_wifi_loading), 11, 0xbfffffff);
            currentPage.wifiNote.setPadding(dp(6), dp(9), dp(6), dp(2));
            currentPage.wifiArea.addView(currentPage.wifiNote);
            currentPage.wifiRows = column();
            currentPage.wifiArea.addView(currentPage.wifiRows);
            Page wifiPage = currentPage;
            column.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (currentPage == wifiPage && !networkEthernet && !closing && fitWifiRows(wifiPage))
                    displayWifiRows(wifiPage, null);
            });
            currentPage.wifiAction = tile(R.drawable.ic_wifi, host.getString(R.string.cc_wifi_refresh), false,
                    this::wifiAction);
            addRow(currentPage.wifiArea, currentPage.wifiAction, 40);
            column.addView(currentPage.wifiArea);
            row(column, R.drawable.ic_wifi, R.string.cc_network_settings,
                    () -> launch(SystemSettings.intent(host, Settings.ACTION_WIFI_SETTINGS)));
            Intent vpn = new Intent(Settings.ACTION_VPN_SETTINGS);
            if (vpn.resolveActivity(host.getPackageManager()) != null) {
                row(column, R.drawable.cc_vpn, R.string.cc_system_vpn, () -> launch(vpn));
            }
            return;
        }
        if ("vpn".equals(page)) {
            currentPage.vpnStatus = info(column, R.drawable.cc_vpn, R.string.cc_vpn);
            for (Map.Entry<String, Intent> app : vpnApps.entrySet()) {
                LinearLayout tile = tile(R.drawable.cc_vpn, app.getKey(), false, () -> launch(new Intent(app.getValue())));
                addRow(column, tile, 44);
            }
            Intent settings = new Intent(Settings.ACTION_VPN_SETTINGS);
            if (settings.resolveActivity(host.getPackageManager()) != null) {
                row(column, R.drawable.ic_settings, R.string.cc_system_vpn, () -> launch(settings));
            } else if (vpnApps.isEmpty()) {
                TextView note = text(host.getString(R.string.cc_vpn_client_hint), 11, 0xbfffffff);
                column.addView(note);
            }
            return;
        }
        LinearLayout top = horizontal(); column.addView(top, new LinearLayout.LayoutParams(-1, dp(104)));
        LinearLayout sleep = tile(R.drawable.cc_power, host.getString(R.string.cc_sleep), true, this::sleep);
        sleep.setTag("firstControl"); addHalf(top, sleep, false);
        LinearLayout right = column(); addHalf(top, right, true);
        LinearLayout network = tile(R.drawable.ic_wifi, host.getString(R.string.quick_network), false, () -> selectAndFocus("network"));
        currentPage.overviewNetwork = (TextView) network.getChildAt(1);
        currentPage.overviewNetworkIcon = (ImageView) network.getChildAt(0);
        right.addView(network, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout settings = tile(R.drawable.cc_settings, host.getString(R.string.settings), false,
                () -> launch(SystemSettings.intent(host, Settings.ACTION_SETTINGS)));
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(-1, dp(48)); settingsLp.topMargin = dp(8);
        right.addView(settings, settingsLp);
        LinearLayout second = horizontal(); addRow(column, second, 48);
        addHalf(second, tile(R.drawable.ic_apps, host.getString(R.string.quick_apps), false,
                () -> launch(SystemSettings.intent(host, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))), false);
        addHalf(second, tile(R.drawable.cc_clock, host.getString(R.string.cc_clock), false,
                () -> launch(new Intent(host, ClockActivity.class))), true);
        LinearLayout shortcuts = horizontal(); shortcuts.setGravity(Gravity.CENTER); addRow(column, shortcuts, 50);
        roundShortcut(shortcuts, R.drawable.cc_bluetooth, R.string.cc_bluetooth,
                () -> launch(SystemSettings.intent(host, Settings.ACTION_BLUETOOTH_SETTINGS)));
        roundShortcut(shortcuts, R.drawable.cc_accessibility, R.string.cc_accessibility,
                () -> launch(SystemSettings.intent(host, Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        roundShortcut(shortcuts, R.drawable.cc_controls, R.string.cc_launcher_settings,
                () -> launch(new Intent(host, LauncherSettingsActivity.class)));
        Intent search = new Intent(android.app.SearchManager.INTENT_ACTION_GLOBAL_SEARCH);
        if (AirPlayControl.available(host)) {
            ImageView button = roundShortcut(shortcuts, R.drawable.cc_airplay, R.string.cc_airplay,
                    () -> airPlay.toggle());
            ((ControlBackdrop) button.getBackground()).preserveImageTint();
            button.setEnabled(false);
            airPlay = new AirPlayControl(host, state -> {
                boolean on = state == AirPlayControl.ON;
                boolean waiting = state == AirPlayControl.STARTING || state == AirPlayControl.STOPPING;
                // A transition must not discard remote focus. toggle() ignores busy presses.
                button.setEnabled(true);
                button.setAlpha(waiting ? .55f : 1f);
                button.setImageTintList(new android.content.res.ColorStateList(
                        new int[][]{new int[]{android.R.attr.state_focused}, new int[0]},
                        new int[]{on ? 0xff0a84ff : 0xff151515, on ? 0xff0a84ff : 0xe6ffffff}));
                int label = waiting ? R.string.cc_airplay_wait : on ? R.string.cc_airplay_on : R.string.cc_airplay_off;
                button.setContentDescription(host.getString(label));
                if (state == AirPlayControl.ERROR) android.widget.Toast.makeText(host,
                        R.string.cc_airplay_error, android.widget.Toast.LENGTH_SHORT).show();
            });
        } else if (search.resolveActivity(host.getPackageManager()) != null) {
            roundShortcut(shortcuts, R.drawable.cc_search, R.string.cc_search, () -> launch(search));
        }
    }

    private void selectAndFocus(String page) {
        for (View tab : tabViews) if (page.equals(tab.getTag())) { tab.requestFocus(); return; }
    }

    void refreshNetwork() {
        String transport = host.getString(R.string.not_connected);
        boolean online = false, ethernet = false, vpn = false;
        if (connectivity != null) for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps == null) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                vpn = true;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                if (!ethernet) online = false;
                ethernet = true;
                transport = "Ethernet";
                online |= caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            } else if (!ethernet && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                transport = "Wi-Fi";
                online |= caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
        }
        if (TextUtils.equals(networkTransport, transport) && networkOnline == online
                && networkEthernet == ethernet && vpnConnected == vpn) return;
        networkTransport = transport; networkOnline = online; networkEthernet = ethernet; vpnConnected = vpn;
        networkRevision++;
        applyNetworkStatus();
        syncWifi();
    }

    private void applyNetworkStatus() {
        int icon = networkEthernet ? R.drawable.cc_ethernet : R.drawable.ic_wifi;
        if (networkTab != null && networkTabIcon != icon) {
            networkTabIcon = icon;
            ((TabStyle) networkTab.getBackground()).setIcon(icon);
        }
        if (currentPage == null || currentPage.networkRevision == networkRevision) return;
        currentPage.networkRevision = networkRevision;
        if (currentPage.overviewNetwork != null) currentPage.overviewNetwork.setText(networkTransport);
        if (currentPage.overviewNetworkIcon != null) transportIcon(currentPage.overviewNetworkIcon, networkEthernet, 20);
        if (currentPage.networkStatusIcon != null) transportIcon(currentPage.networkStatusIcon, networkEthernet, 36);
        if (currentPage.networkStatus != null) setTextIfChanged(currentPage.networkStatus,
                !networkEthernet && pendingWifi != null && !pendingWifi.connectedSsid.isEmpty()
                        ? pendingWifi.connectedSsid : host.getString(R.string.cc_network_status, networkTransport,
                        host.getString(networkOnline ? R.string.cc_online : R.string.cc_no_internet)));
        if (currentPage.vpnStatus != null) currentPage.vpnStatus.setText(host.getString(vpnConnected ? R.string.cc_vpn_connected : R.string.cc_vpn_disconnected));
    }

    private void syncWifi() {
        Page page = pages.get("network");
        if (page != null) {
            boolean restoreFocus = networkEthernet && page.wifiArea.hasFocus();
            page.wifiArea.setVisibility(networkEthernet ? View.GONE : View.VISIBLE);
            if (restoreFocus) page.content.findViewWithTag("firstControl").requestFocus();
        }
        if (!"network".equals(selected) || networkEthernet || closing) { stopWifi(); return; }
        if (wifi == null) wifi = new WifiScanner(host, snapshot -> {
            pendingWifi = snapshot;
            root.removeCallbacks(renderWifi);
            // Build newly discovered rows after the page crossfade, not during its frames.
            if (outgoingPage != null) root.postDelayed(renderWifi, 160);
            else renderWifiNetworks();
        });
        wifi.start();
    }

    private void stopWifi() {
        if (wifi != null) wifi.stop();
        if (root != null) root.removeCallbacks(renderWifi);
        pendingWifi = null;
    }

    private void wifiAction() {
        if (pendingWifi != null && pendingWifi.state == WifiScanner.State.PERMISSION_REQUIRED) {
            launch(new Intent(host, WifiPermissionActivity.class));
        } else if (pendingWifi != null && pendingWifi.state == WifiScanner.State.LOCATION_REQUIRED) {
            launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } else if (pendingWifi != null && pendingWifi.state == WifiScanner.State.DISABLED) {
            launch(SystemSettings.intent(host, Settings.ACTION_WIFI_SETTINGS));
        } else if (wifi != null) wifi.requestScan();
    }

    private void renderWifiNetworks() {
        if (closing || !"network".equals(selected) || networkEthernet || pendingWifi == null) return;
        Page page = currentPage;
        WifiScanner.Snapshot snapshot = pendingWifi;
        int message = switch (snapshot.state) {
            case READY -> snapshot.networks.isEmpty() ? R.string.cc_wifi_empty : R.string.cc_wifi_available;
            case PERMISSION_REQUIRED -> R.string.cc_wifi_permission;
            case LOCATION_REQUIRED -> R.string.cc_wifi_location;
            case DISABLED -> R.string.cc_wifi_disabled;
            case NO_WIFI -> R.string.cc_wifi_unavailable;
            case ERROR -> R.string.cc_wifi_error;
        };
        setTextIfChanged(page.wifiNote, host.getString(message));
        int action = switch (snapshot.state) {
            case PERMISSION_REQUIRED -> R.string.cc_wifi_allow;
            case LOCATION_REQUIRED -> R.string.cc_wifi_location_action;
            case DISABLED -> R.string.cc_network_settings;
            default -> R.string.cc_wifi_refresh;
        };
        setTextIfChanged((TextView) ((LinearLayout) page.wifiAction).getChildAt(1), host.getString(action));
        page.wifiAction.setContentDescription(host.getString(action));
        boolean noAdapter = snapshot.state == WifiScanner.State.NO_WIFI;
        if (noAdapter && page.wifiAction.hasFocus()) page.content.findViewWithTag("firstControl").requestFocus();
        page.wifiAction.setVisibility(noAdapter ? View.GONE : View.VISIBLE);
        if (!snapshot.connectedSsid.isEmpty()) {
            setTextIfChanged(page.networkStatus, snapshot.connectedSsid);
        } else {
            setTextIfChanged(page.networkStatus, host.getString(R.string.cc_network_status, networkTransport,
                    host.getString(networkOnline ? R.string.cc_online : R.string.cc_no_internet)));
        }
        java.util.Set<String> present = new java.util.HashSet<>();
        boolean enterFirst = page.wifiEntries.isEmpty() && page.wifiAction.hasFocus();
        boolean changed = false, restoreFocus = false;
        for (WifiAccessPoints.Entry entry : snapshot.networks) present.add(entry.key);
        java.util.Iterator<Map.Entry<String, WifiRow>> old = page.wifiEntries.entrySet().iterator();
        while (old.hasNext()) {
            Map.Entry<String, WifiRow> entry = old.next();
            if (present.contains(entry.getKey())) continue;
            WifiRow row = entry.getValue();
            if (row.view.hasFocus()) {
                restoreFocus = true;
                page.wifiAction.requestFocus();
            }
            ControlBackdrop.finishAnimations(row.view);
            page.wifiRows.removeView(row.view); old.remove(); changed = true;
        }
        for (WifiAccessPoints.Entry entry : snapshot.networks) {
            WifiRow row = page.wifiEntries.get(entry.key);
            if (row == null) {
                row = new WifiRow(page, entry);
                page.wifiEntries.put(entry.key, row);
                page.wifiOrder.add(entry.key);
                changed = true;
            } else if (!entry.equals(row.entry)) row.bind(entry);
        }
        page.wifiOrder.removeIf(key -> !present.contains(key));
        boolean resized = fitWifiRows(page);
        if (changed || resized) displayWifiRows(page, null);
        // Keep existing rows in place while signal levels change: remote focus must not jump.
        if (restoreFocus) page.wifiAction.requestFocus();
        else if (enterFirst && page.wifiRows.getChildCount() > 0) page.wifiRows.getChildAt(0).requestFocus();
        if (changed) { pagePositionsDirty = true; body.requestLayout(); }
    }

    private static void setTextIfChanged(TextView label, CharSequence value) {
        if (!TextUtils.equals(label.getText(), value)) label.setText(value);
    }

    private boolean fitWifiRows(Page page) {
        // Layout-change listeners run before View marks its first layout as complete.
        // The assigned bounds are already usable there, unlike isLaidOut().
        if (page.content.getHeight() == 0) return false;
        int fixed = page.content.getHeight() - page.wifiRows.getHeight();
        int available = wifiBodyHeight - pageScroll.getPaddingTop() - pageScroll.getPaddingBottom() - fixed;
        return page.wifiViewport.fit(available, dp(44) + dp(7), page.wifiOrder.size());
    }

    private void displayWifiRows(Page page, String focusKey) {
        if (focusKey == null) for (WifiRow row : page.wifiEntries.values())
            if (row.view.hasFocus()) { focusKey = row.entry.key; break; }
        int focus = focusKey == null ? -1 : page.wifiOrder.indexOf(focusKey);
        if (focus >= 0) page.wifiViewport.reveal(focus, page.wifiOrder.size());
        int first = page.wifiViewport.first(), end = page.wifiViewport.end(page.wifiOrder.size());
        boolean same = page.wifiRows.getChildCount() == end - first;
        for (int i = first; same && i < end; i++)
            same = page.wifiRows.getChildAt(i - first) == page.wifiEntries.get(page.wifiOrder.get(i)).view;
        if (!same) {
            // Attach the destination before removing the focused row. Detaching focus first lets
            // Android select the first header tab, which would switch the entire page mid-navigation.
            List<View> visible = new ArrayList<>(end - first);
            for (int i = first; i < end; i++) {
                View row = page.wifiEntries.get(page.wifiOrder.get(i)).view;
                visible.add(row);
                if (row.getParent() == null) addRow(page.wifiRows, row, 44);
            }
            if (focus >= first && focus < end) page.wifiEntries.get(focusKey).view.requestFocus();
            for (int i = page.wifiRows.getChildCount() - 1; i >= 0; i--) {
                View row = page.wifiRows.getChildAt(i);
                if (!visible.contains(row)) {
                    ControlBackdrop.finishAnimations(row);
                    page.wifiRows.removeViewAt(i);
                }
            }
            for (View row : visible) page.wifiRows.bringChildToFront(row);
            pagePositionsDirty = true;
            body.requestLayout();
        }
        if (focus >= first && focus < end) page.wifiEntries.get(focusKey).view.requestFocus();
    }

    private final class WifiRow {
        final LinearLayout view = horizontal();
        final ImageView signal = new ImageView(host);
        final WifiSignal signalDrawable = new WifiSignal();
        final TextView label;
        final ImageView state = new ImageView(host);
        WifiAccessPoints.Entry entry;

        WifiRow(Page page, WifiAccessPoints.Entry value) {
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(12), dp(5), dp(12), dp(5));
            view.setId(View.generateViewId()); view.setFocusable(true); view.setClickable(true);
            signal.setImageTintList(android.content.res.ColorStateList.valueOf(0xff58b2ff));
            signal.setImageDrawable(signalDrawable);
            view.addView(signal, new LinearLayout.LayoutParams(dp(20), dp(20)));
            label = text(value.ssid, 12, 0xe6ffffff);
            label.setMaxLines(1); label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams title = new LinearLayout.LayoutParams(0, -2, 1);
            title.setMarginStart(dp(9)); title.setMarginEnd(dp(7)); view.addView(label, title);
            state.setImageTintList(android.content.res.ColorStateList.valueOf(0xbfffffff));
            view.addView(state, new LinearLayout.LayoutParams(dp(14), dp(14)));
            bind(value);
            view.setBackground(material(view, 23));
            view.setOnClickListener(v -> launch(WifiConnectionIntent.forNetwork(host, entry)));
            view.setOnKeyListener((v, key, event) -> {
                if (key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
                int next = page.wifiOrder.indexOf(entry.key) + (key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                if (next < 0 || next >= page.wifiOrder.size()) return false;
                displayWifiRows(page, page.wifiOrder.get(next));
                return true;
            });
        }

        void bind(WifiAccessPoints.Entry value) {
            entry = value;
            signalDrawable.setBars(value.bars);
            state.setImageResource(value.connected ? R.drawable.library_check : R.drawable.ic_lock);
            state.setVisibility(value.connected || value.security.secured ? View.VISIBLE : View.INVISIBLE);
            view.setContentDescription(value.ssid + (value.connected ? ", " + host.getString(R.string.cc_wifi_connected) : ""));
        }
    }

    /** Four tiny strokes, reused by the row; no bitmap or per-frame allocations. */
    private static final class WifiSignal extends Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF arc = new android.graphics.RectF();
        private int bars = -1, alpha = 255;
        WifiSignal() { paint.setStrokeCap(android.graphics.Paint.Cap.ROUND); }
        void setBars(int value) { if (bars != value) { bars = value; invalidateSelf(); } }
        @Override public void draw(android.graphics.Canvas canvas) {
            Rect bounds = getBounds();
            float unit = Math.min(bounds.width(), bounds.height()) / 24f;
            canvas.save(); canvas.translate(bounds.left, bounds.top); canvas.scale(unit, unit);
            paint.setColor(Color.WHITE); paint.setStyle(android.graphics.Paint.Style.STROKE); paint.setStrokeWidth(2.4f);
            for (int i = 1; i <= 3; i++) {
                float radius = 3 + i * 4;
                arc.set(12 - radius, 21 - radius, 12 + radius, 21 + radius);
                paint.setAlpha(bars >= i + 1 ? alpha : alpha * 55 / 255);
                canvas.drawArc(arc, 225, 90, false, paint);
            }
            paint.setStyle(android.graphics.Paint.Style.FILL); paint.setAlpha(bars > 0 ? alpha : alpha * 55 / 255);
            canvas.drawCircle(12, 20, 1.6f, paint); canvas.restore();
        }
        @Override public void setAlpha(int value) { if (alpha != value) { alpha = value; invalidateSelf(); } }
        @Override public void setColorFilter(android.graphics.ColorFilter value) { paint.setColorFilter(value); invalidateSelf(); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private void transportIcon(ImageView image, boolean ethernet, int heightDp) {
        image.setImageResource(ethernet ? R.drawable.cc_ethernet : R.drawable.ic_wifi);
        int width = dp(ethernet ? heightDp * 28f / 24f : heightDp);
        if (image.getLayoutParams().width != width) {
            image.getLayoutParams().width = width;
            image.requestLayout();
        }
    }

    private void sleep() { closeThen(actions::controlSleep); }

    private void launch(Intent intent) {
        closeThen(() -> actions.controlOpen(intent));
    }

    private TextView info(LinearLayout column, int icon, int title) {
        LinearLayout card = tile(icon, host.getString(title), true, null);
        card.setFocusable(false); card.setClickable(false);
        View image = card.getChildAt(0);
        image.getLayoutParams().width = image.getLayoutParams().height = dp(36);
        column.addView(card, new LinearLayout.LayoutParams(-1, dp(96)));
        return (TextView) card.getChildAt(1);
    }

    private void row(LinearLayout column, int icon, int title, Runnable action) {
        LinearLayout row = tile(icon, host.getString(title), false, action);
        if (column.findViewWithTag("firstControl") == null) row.setTag("firstControl");
        addRow(column, row, 44);
    }

    private LinearLayout tile(int icon, String label, boolean large, Runnable action) {
        LinearLayout tile = large ? column() : horizontal(); tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(5), dp(4), dp(5), dp(4));
        tile.setBackground(material(tile, 23)); tile.setFocusable(true); tile.setClickable(true);
        tile.setId(View.generateViewId()); tile.setContentDescription(label);
        ImageView image = new ImageView(host); image.setImageResource(icon);
        image.setImageTintList(android.content.res.ColorStateList.valueOf(icon == R.drawable.ic_wifi ? 0xff58b2ff : 0xe6ffffff));
        tile.addView(image, new LinearLayout.LayoutParams(dp(large ? 64 : 20), dp(large ? 64 : 20)));
        TextView title = text(label, 11, 0xe6ffffff);
        title.setGravity(Gravity.CENTER); title.setMaxLines(large ? 2 : 1); title.setEllipsize(TextUtils.TruncateAt.END);
        if (!large) title.setAutoSizeTextTypeUniformWithConfiguration(9, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(large ? -1 : -2, -2);
        if (large) titleLp.topMargin = dp(4); else titleLp.setMarginStart(dp(5));
        tile.addView(title, titleLp);
        if (action != null) tile.setOnClickListener(v -> action.run());
        return tile;
    }

    private ImageView roundShortcut(LinearLayout parent, int icon, int label, Runnable action) {
        FrameLayout slot = new FrameLayout(host);
        slot.setClipChildren(false); slot.setClipToPadding(false);
        LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(0, -1, 1f);
        if (parent.getChildCount() > 0) slotLp.setMarginStart(dp(8));
        parent.addView(slot, slotLp);
        ImageView button = new ImageView(host); button.setImageResource(icon);
        button.setContentDescription(host.getString(label));
        button.setImageTintList(android.content.res.ColorStateList.valueOf(0xe6ffffff));
        button.setPadding(dp(13), dp(13), dp(13), dp(13));
        button.setFocusable(true); button.setClickable(true); button.setId(View.generateViewId());
        button.setBackground(material(button, 24));
        slot.addView(button, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private Drawable material(View tile, float radius) {
        if (tile != clock) tile.setElevation(dp(2));
        return new ControlBackdrop(tile, backdrop, density, dp(radius), backdropScale, 0x33211d25,
                animate && !reduced, transparentMaterial).whiteFocus();
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(host); view.setTypeface(size >= 12 ? medium : regular);
        view.setIncludeFontPadding(false); view.setTextSize(size); view.setTextColor(color); view.setText(value);
        return view;
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(host); v.setOrientation(LinearLayout.VERTICAL); v.setClipChildren(false); return v; }
    private LinearLayout horizontal() { LinearLayout v = new LinearLayout(host); v.setOrientation(LinearLayout.HORIZONTAL); v.setClipChildren(false); return v; }
    private void addHalf(LinearLayout parent, View child, boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); if (gap) lp.setMarginStart(dp(8)); parent.addView(child, lp);
    }
    private void addRow(LinearLayout parent, View child, int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(height)); lp.topMargin = dp(7); parent.addView(child, lp);
    }
    private int dp(float value) { return Math.round(value * density); }
}
