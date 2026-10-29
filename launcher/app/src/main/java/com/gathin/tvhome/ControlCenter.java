package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Intent;
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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
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
    private final MainActivity host;
    private final FrameLayout stage;
    private final ViewGroup home;
    private int homeFocusability, homeAccessibility;
    private View homeFocus;
    private boolean showing;
    private final View anchor;
    private final View capsule, sourceClock;
    private final TextClock clock;
    private final FrameLayout root;
    private final View scrim;
    private final LinearLayout panel, tabs;
    private final FrameLayout body;
    private LinearLayout pageContent;
    private View outgoingPage;
    private final float density;
    private final boolean animate, reduced;
    private final Typeface regular, medium;
    private final Bitmap backdrop;
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
    private static final java.util.concurrent.Executor GLASS_WORK = new java.util.concurrent.ThreadPoolExecutor(
            0, 1, 2, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(1),
            task -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "PeachGlass"), new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    private String selected = "";
    private View selectedTab;
    private TextView overviewNetwork, networkStatus, vpnStatus;
    private ImageView overviewNetworkIcon, networkStatusIcon;
    private final ConnectivityManager connectivity;

    private static final FloatPropertyCompat<ControlCenter> REVEAL = new FloatPropertyCompat<ControlCenter>("controlReveal") {
        @Override public float getValue(ControlCenter center) { return center.reveal; }
        @Override public void setValue(ControlCenter center, float value) { center.applyReveal(value); }
    };

    ControlCenter(MainActivity host, View anchor, boolean inputs, boolean animate, boolean reduced, String vpnApp) {
        this.host = host; this.anchor = anchor; this.animate = animate; this.reduced = reduced;
        stage = host.findViewById(android.R.id.content);
        home = host.findViewById(R.id.root);
        capsule = host.findViewById(R.id.topButtons);
        sourceClock = host.findViewById(R.id.compactClock);
        density = host.getResources().getDisplayMetrics().density;
        View content = stage;
        boolean glass = host.getSharedPreferences(Config.SETTINGS_NAME, 0).getBoolean("liquid_glass", true);
        backdrop = glass ? ControlBackdrop.snapshot(content) : null;
        backdropScale = backdrop == null ? 1f : content.getWidth() / (float) backdrop.getWidth();
        regular = host.getResources().getFont(R.font.inter_regular);
        medium = host.getResources().getFont(R.font.inter_medium);
        connectivity = host.getSystemService(ConnectivityManager.class);
        PackageManager pm = host.getPackageManager();
        // Installed clients are not presented as saved profiles: only the owning app can read those.
        for (ResolveInfo info : pm.queryIntentServices(new Intent(VpnService.SERVICE_INTERFACE), 0)) {
            String pkg = info.serviceInfo.packageName;
            if (!"android.permission.BIND_VPN_SERVICE".equals(info.serviceInfo.permission)) continue;
            Intent target = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (target == null) target = pm.getLaunchIntentForPackage(pkg);
            if (target != null) vpnApps.put(String.valueOf(info.serviceInfo.applicationInfo.loadLabel(pm)), target);
        }
        if (!vpnApp.isEmpty()) {
            Intent target = pm.getLeanbackLaunchIntentForPackage(vpnApp);
            if (target == null) target = pm.getLaunchIntentForPackage(vpnApp);
            if (target != null) vpnApps.put(host.getString(R.string.cc_vpn_app), target);
        }

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
        scrim = new View(host); scrim.setBackgroundColor(0x50000000);
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
        clock.setFontFeatureSettings("tnum");
        clock.setTypeface(medium); clock.setTextSize(12.5f); clock.setTextColor(0xbfffffff);
        clock.setIncludeFontPadding(false);
        clock.setGravity(Gravity.CENTER);
        clock.setPadding(dp(9), 0, dp(9), 0);
        clock.setBackground(material(clock, 14));
        header.addView(clock, new LinearLayout.LayoutParams(-2, dp(28)));
        header.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
        tabs = new LinearLayout(host); tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setClipChildren(false); tabs.setClipToPadding(false);
        header.addView(tabs);
        if (hasVpn() || !vpnApps.isEmpty()) addTab("vpn", R.drawable.cc_vpn, R.string.cc_vpn);
        addTab("overview", R.drawable.ic_settings, R.string.cc_title);
        overviewTab = tabViews.get(tabViews.size() - 1);
        if (inputs) addTab("inputs", R.drawable.ic_input, R.string.inputs);
        addTab("network", R.drawable.ic_wifi, R.string.quick_network);

        final int maxBodyHeight = Math.min(dp(320), host.getResources().getDisplayMetrics().heightPixels - dp(96));
        ScrollView scroll = new ScrollView(host) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxBodyHeight, MeasureSpec.AT_MOST));
            }
        };
        scroll.setPadding(dp(4), dp(4), dp(4), dp(4));
        scroll.setClipChildren(false); scroll.setClipToPadding(false);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false); scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, -2);
        scrollLp.topMargin = dp(8); panel.addView(scroll, scrollLp);
        body = new FrameLayout(host); body.setClipChildren(false); scroll.addView(body);
        select("overview");
    }

    boolean isShowing() { return showing; }

    boolean dispatchKeyEvent(KeyEvent event) { return root.dispatchKeyEvent(event); }

    void show() {
        panel.setAlpha(0f); scrim.setAlpha(0f);
        Runnable reveal = () -> {
            if (!showing || closing) return;
            int[] from = new int[2], to = new int[2];
            sourceClock.getLocationOnScreen(from); clock.getLocationOnScreen(to);
            clockDx = from[0] + sourceClock.getWidth() * .5f - to[0] - clock.getWidth() * .5f;
            clockDy = from[1] + sourceClock.getHeight() * .5f - to[1] - clock.getHeight() * .5f;
            anchor.getLocationOnScreen(from); overviewTab.getLocationOnScreen(to);
            gearDx = from[0] + anchor.getWidth() * anchor.getScaleX() * .5f - to[0] - overviewTab.getWidth() * .5f;
            gearDy = from[1] + anchor.getHeight() * anchor.getScaleY() * .5f - to[1] - overviewTab.getHeight() * .5f;
            gearScale = anchor.getWidth() * anchor.getScaleX() / overviewTab.getWidth();
            body.setPivotX(body.getWidth() * .5f); body.setPivotY(body.getHeight() * .5f);
            if (animate && !reduced) {
                // Build the static controls once before the spring, rather than rasterizing all their
                // clipped glass paths during every alpha/scale frame. Released as soon as motion ends.
                body.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                body.buildLayer();
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
                        body.setLayerType(View.LAYER_TYPE_NONE, null);
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
        };
        if (backdrop != null) {
            View content = stage;
            int[] location = new int[2];
            content.getLocationInWindow(location);
            Rect source = new Rect(location[0], location[1],
                    location[0] + content.getWidth(), location[1] + content.getHeight());
            long copyStart = android.os.SystemClock.elapsedRealtime();
            // Copy the existing window frame asynchronously. A software content.draw() traversed every
            // view on the UI thread and could not draw a wallpaper held in a hardware bitmap.
            try {
                PixelCopy.request(host.getWindow(), source, backdrop, result -> {
                    if (closing) return;
                    android.util.Log.d("PeachControls", "Window copy " + result + " in "
                            + (android.os.SystemClock.elapsedRealtime() - copyStart) + "ms");
                    if (result != PixelCopy.SUCCESS) {
                        backdrop.eraseColor(0xff29262f);
                        panel.post(reveal);
                        return;
                    }
                    GLASS_WORK.execute(() -> {
                        if (closing) return;
                        ControlBackdrop.blur(backdrop);
                        panel.post(reveal);
                    });
                }, new Handler(Looper.getMainLooper()));
            } catch (IllegalArgumentException noWindowFrame) {
                // A window opened before its first draw may not have a backing surface yet.
                android.util.Log.w("PeachControls", "Window has no readable frame yet; using matte controls");
                panel.post(reveal);
            }
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
        if (backdrop == null) panel.post(reveal);
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
        clock.setAlpha(handoff); overviewTab.setAlpha(handoff);
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
        if (closing) return;
        closing = true; returnToGear = action == null; afterClose = action;
        if (returnToGear) overviewTab.getBackground().setState(new int[]{android.R.attr.state_focused});
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
        body.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        spring.getSpring().setStiffness(900f).setDampingRatio(1f);
        spring.animateToFinalPosition(0f);
    }

    private void finishDismiss() {
        if (!showing) return;
        showing = false;
        closing = true;
        if (spring != null) spring.cancel();
        body.setLayerType(View.LAYER_TYPE_NONE, null);
        capsule.animate().cancel();
        clearOutgoingPage();
        if (pageContent != null) pageContent.animate().cancel();
        for (View tab : tabViews) tab.getBackground().jumpToCurrentState();
        home.setDescendantFocusability(homeFocusability);
        home.setImportantForAccessibility(homeAccessibility);
        sourceClock.setAlpha(1f); anchor.setAlpha(1f);
        capsule.setAlpha(1f); capsule.setVisibility(View.VISIBLE);
        if (!returnToGear && homeFocus != null) homeFocus.requestFocusFromTouch();
        host.controlCenterDismissed(this, returnToGear);
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
            View first = body.findViewWithTag("firstControl");
            if (first != null) first.requestFocus();
        });
    }

    /** One reusable color animator per tab; interrupted transitions continue from the displayed colors. */
    private static final class TabStyle extends GradientDrawable {
        private final ImageView tab;
        private final Drawable icon;
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
        selected = page;
        for (View tab : tabViews) {
            boolean active = page.equals(tab.getTag());
            tab.setSelected(active);
            if (active) selectedTab = tab;
        }
        overviewNetwork = null; networkStatus = null; vpnStatus = null;
        overviewNetworkIcon = null; networkStatusIcon = null;
        clearOutgoingPage();
        LinearLayout previous = pageContent;
        boolean crossfade = previous != null && previous.isLaidOut() && revealed && animate && !reduced && !closing;
        // A shorter destination must not clip the fading bottom rows of the previous page.
        if (crossfade) {
            body.setMinimumHeight(body.getHeight());
            ControlBackdrop.freezePositions(previous);
        }
        if (previous != null) previous.animate().cancel();
        body.removeAllViews();
        LinearLayout column = column(); pageContent = column;
        body.addView(column, new FrameLayout.LayoutParams(-1, -2));
        populatePage(page, column);
        if (!crossfade) return;
        // A visual-only overlay cannot receive focus, clicks or accessibility traversal.
        // Keep at most the previous and current pages, even when keys repeat during a transition.
        outgoingPage = previous;
        body.getOverlay().add(previous);
        previous.animate().alpha(0f).setDuration(140).withLayer()
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    body.getOverlay().remove(previous);
                    if (outgoingPage == previous) {
                        outgoingPage = null;
                        body.setMinimumHeight(0);
                    }
                }).start();
        column.setAlpha(0f);
        column.animate().alpha(1f).setDuration(140).withLayer().setInterpolator(new DecelerateInterpolator()).start();
    }

    private void clearOutgoingPage() {
        if (outgoingPage == null) return;
        outgoingPage.animate().cancel();
        body.getOverlay().remove(outgoingPage);
        outgoingPage = null;
        body.setMinimumHeight(0);
    }

    private void populatePage(String page, LinearLayout column) {
        if ("inputs".equals(page)) {
            host.controlInputRows(column, this::closeThen);
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
            networkStatus = info(column, R.drawable.ic_wifi, R.string.quick_network);
            networkStatusIcon = (ImageView) ((LinearLayout) networkStatus.getParent()).getChildAt(0);
            row(column, R.drawable.ic_wifi, R.string.cc_network_settings,
                    () -> launch(SystemSettings.intent(host, Settings.ACTION_WIFI_SETTINGS)));
            Intent vpn = new Intent(Settings.ACTION_VPN_SETTINGS);
            if (vpn.resolveActivity(host.getPackageManager()) != null) {
                row(column, R.drawable.cc_vpn, R.string.cc_system_vpn, () -> launch(vpn));
            }
            refreshNetwork(); return;
        }
        if ("vpn".equals(page)) {
            vpnStatus = info(column, R.drawable.cc_vpn, R.string.cc_vpn);
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
            refreshNetwork(); return;
        }
        LinearLayout top = horizontal(); column.addView(top, new LinearLayout.LayoutParams(-1, dp(104)));
        LinearLayout sleep = tile(R.drawable.cc_power, host.getString(R.string.cc_sleep), true, this::sleep);
        sleep.setTag("firstControl"); addHalf(top, sleep, false);
        LinearLayout right = column(); addHalf(top, right, true);
        LinearLayout network = tile(R.drawable.ic_wifi, host.getString(R.string.quick_network), false, () -> selectAndFocus("network"));
        overviewNetwork = (TextView) network.getChildAt(1);
        overviewNetworkIcon = (ImageView) network.getChildAt(0);
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
        if (search.resolveActivity(host.getPackageManager()) != null) {
            roundShortcut(shortcuts, R.drawable.cc_search, R.string.cc_search, () -> launch(search));
        }
        refreshNetwork();
    }

    private void selectAndFocus(String page) {
        for (View tab : tabViews) if (page.equals(tab.getTag())) { tab.requestFocus(); return; }
    }

    void refreshNetwork() {
        String transport = host.getString(R.string.not_connected);
        boolean online = false, ethernet = false;
        if (connectivity != null) for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                transport = ethernet ? "Ethernet" : "Wi-Fi";
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                break;
            }
        }
        if (overviewNetwork != null) overviewNetwork.setText(transport);
        if (overviewNetworkIcon != null) transportIcon(overviewNetworkIcon, ethernet, 20);
        if (networkStatusIcon != null) transportIcon(networkStatusIcon, ethernet, 36);
        if (networkStatus != null) networkStatus.setText(host.getString(R.string.cc_network_status, transport, host.getString(online ? R.string.cc_online : R.string.cc_no_internet)));
        if (vpnStatus != null) vpnStatus.setText(host.getString(hasVpn() ? R.string.cc_vpn_connected : R.string.cc_vpn_disconnected));
    }

    private void transportIcon(ImageView image, boolean ethernet, int heightDp) {
        image.setImageResource(ethernet ? R.drawable.cc_ethernet : R.drawable.ic_wifi);
        int width = dp(ethernet ? heightDp * 28f / 24f : heightDp);
        if (image.getLayoutParams().width != width) {
            image.getLayoutParams().width = width;
            image.requestLayout();
        }
    }

    private boolean hasVpn() {
        if (connectivity != null) for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    private void sleep() {
        closeThen(() -> {
        if (Build.VERSION.SDK_INT >= 28 && StandbyService.sleep()) return;
        new AlertDialog.Builder(host).setTitle(R.string.cc_sleep)
                .setMessage(Build.VERSION.SDK_INT >= 28 ? R.string.cc_standby_help : R.string.cc_sleep_unavailable)
                .setPositiveButton(R.string.settings, (d, w) -> host.controlOpen(SystemSettings.intent(host, Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton(android.R.string.cancel, null).show();
        });
    }

    private void launch(Intent intent) {
        closeThen(() -> host.controlOpen(intent));
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
        tile.setOnFocusChangeListener((v, focus) -> {
            if (!animate || reduced) return;
            v.animate().scaleX(focus ? 1.025f : 1f).scaleY(focus ? 1.025f : 1f)
                    .setDuration(120).setInterpolator(new DecelerateInterpolator()).start();
        });
        return tile;
    }

    private void roundShortcut(LinearLayout parent, int icon, int label, Runnable action) {
        FrameLayout slot = new FrameLayout(host);
        LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(dp(48), -1);
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
    }

    private Drawable material(View tile, float radius) {
        if (tile != clock) tile.setElevation(dp(2));
        return new ControlBackdrop(tile, backdrop, density, dp(radius), backdropScale, 0x33211d25, animate && !reduced);
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
