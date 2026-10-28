package com.gathin.tvhome;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compact controls with one shared frosted backdrop and a scale-only opening spring. */
final class ControlCenter {
    final PopupWindow popup;
    private final MainActivity host;
    private final View anchor;
    private final FrameLayout root;
    private final View scrim;
    private final LinearLayout panel, tabs;
    private final FrameLayout body;
    private final float density;
    private final boolean animate, reduced;
    private final Typeface regular, medium;
    private final Bitmap backdrop;
    private final float backdropScale;
    private final List<View> tabViews = new ArrayList<>();
    private final Map<String, Intent> vpnApps = new LinkedHashMap<>();
    private SpringAnimation spring;
    private volatile boolean closing;
    private boolean dismissReady, returnToGear;
    private Runnable afterClose;
    private static final java.util.concurrent.Executor GLASS_WORK = new java.util.concurrent.ThreadPoolExecutor(
            0, 1, 2, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
    private String selected = "";
    private TextView overviewNetwork, networkStatus, vpnStatus;
    private final ConnectivityManager connectivity;

    private static final FloatPropertyCompat<View> OPEN_SCALE = new FloatPropertyCompat<View>("controlScale") {
        @Override public float getValue(View view) { return view.getScaleX(); }
        @Override public void setValue(View view, float value) { view.setScaleX(value); view.setScaleY(value); }
    };

    ControlCenter(MainActivity host, View anchor, boolean inputs, boolean animate, boolean reduced, String vpnApp) {
        this.host = host; this.anchor = anchor; this.animate = animate; this.reduced = reduced;
        density = host.getResources().getDisplayMetrics().density;
        View content = host.findViewById(android.R.id.content);
        boolean glass = host.getSharedPreferences(Config.SETTINGS_NAME, 0).getBoolean("liquid_glass", true);
        host.findViewById(R.id.topButtons).setVisibility(View.INVISIBLE);
        long captureStart = android.os.SystemClock.elapsedRealtime();
        backdrop = glass ? ControlBackdrop.capture(content) : null;
        backdropScale = backdrop == null ? 1f : content.getWidth() / (float) backdrop.getWidth();
        android.util.Log.d("PeachControls", "Snapshot prepared in " + (android.os.SystemClock.elapsedRealtime() - captureStart) + "ms");
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
        panel.setBackground(material(panel, 28));
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(dp(244), -2, Gravity.TOP | Gravity.END);
        panelLp.topMargin = dp(16); panelLp.setMarginEnd(dp(22));
        root.addView(panel, panelLp);

        LinearLayout header = new LinearLayout(host);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, dp(2), 0);
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(30)));
        TextClock clock = new TextClock(host);
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
        header.addView(tabs);
        addTab("overview", R.drawable.cc_controls, R.string.cc_title);
        addTab("network", R.drawable.ic_wifi, R.string.quick_network);
        if (inputs) addTab("inputs", R.drawable.ic_input, R.string.inputs);
        if (hasVpn() || !vpnApps.isEmpty()) addTab("vpn", R.drawable.cc_vpn, R.string.cc_vpn);

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
        popup = new PopupWindow(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true) {
            @Override public void dismiss() {
                // PopupDecorView intercepts Back before the content view. Route that path through the fold too.
                if (dismissReady) super.dismiss(); else close();
            }
        };
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setAnimationStyle(0); popup.setElevation(0); popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setOnDismissListener(() -> {
            closing = true;
            if (spring != null) spring.cancel();
            panel.animate().cancel(); scrim.animate().cancel();
            host.findViewById(R.id.topButtons).setVisibility(View.VISIBLE);
            host.controlCenterDismissed(this, returnToGear);
            Runnable action = afterClose; afterClose = null;
            if (action != null) action.run();
        });
        select("overview");
    }

    void show() {
        int[] origin = new int[2]; anchor.getLocationOnScreen(origin);
        panel.setAlpha(0f); scrim.setAlpha(0f);
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, 0, 0);
        host.findViewById(R.id.topButtons).setVisibility(View.INVISIBLE);
        Runnable reveal = () -> {
            if (!popup.isShowing() || closing) return;
            int[] location = new int[2]; panel.getLocationOnScreen(location);
            panel.setPivotX(origin[0] + anchor.getWidth() * .5f - location[0]);
            panel.setPivotY(origin[1] + anchor.getHeight() * .5f - location[1]);
            if (animate) {
                panel.animate().alpha(1f).setDuration(reduced ? 90 : 130).start();
                scrim.animate().alpha(1f).setDuration(reduced ? 90 : 180).start();
                if (!reduced) {
                    panel.setScaleX(.35f); panel.setScaleY(.35f);
                    spring = new SpringAnimation(panel, OPEN_SCALE);
                    spring.setSpring(new SpringForce(1f).setStiffness(700f).setDampingRatio(.66f));
                    spring.setMinimumVisibleChange(.001f); spring.start();
                }
            }
            if (!animate) { panel.setAlpha(1f); scrim.setAlpha(1f); }
            tabViews.get(0).requestFocusFromTouch();
        };
        if (backdrop == null) panel.post(reveal);
        else GLASS_WORK.execute(() -> {
            if (closing) return;
            ControlBackdrop.blur(backdrop);
            panel.post(reveal);
        });
    }

    void close() { closeThen(null); }

    void closeThen(Runnable action) {
        if (closing) return;
        closing = true; returnToGear = action == null; afterClose = action;
        if (spring != null) spring.cancel();
        panel.animate().cancel(); scrim.animate().cancel();
        if (!animate || reduced) { finishDismiss(); return; }
        scrim.animate().alpha(0f).setDuration(190).start();
        panel.animate().alpha(0f).scaleX(.28f).scaleY(.28f).setDuration(210)
                .setInterpolator(new android.view.animation.PathInterpolator(.3f, 0f, .65f, 1f))
                .withEndAction(this::finishDismiss).start();
    }

    private void finishDismiss() {
        dismissReady = true;
        popup.dismiss();
    }

    void dismissImmediately() {
        returnToGear = false; afterClose = null;
        closing = true;
        if (spring != null) spring.cancel();
        panel.animate().cancel(); scrim.animate().cancel();
        finishDismiss();
    }

    private void addTab(String key, int icon, int title) {
        ImageView tab = new ImageView(host);
        tab.setId(View.generateViewId()); tab.setImageResource(icon); tab.setImageTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_selected}, new int[]{}},
                new int[]{0xff29272d, 0xcfffffff}));
        tab.setPadding(dp(6), dp(6), dp(6), dp(6));
        tab.setContentDescription(host.getString(title)); tab.setFocusable(true); tab.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable active = new GradientDrawable(); active.setShape(GradientDrawable.OVAL); active.setColor(0xf2ffffff);
        bg.addState(new int[]{android.R.attr.state_selected}, active);
        GradientDrawable focused = new GradientDrawable(); focused.setShape(GradientDrawable.OVAL); focused.setColor(0x30ffffff);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        tab.setBackground(bg); tab.setTag(key);
        tabs.addView(tab, new LinearLayout.LayoutParams(dp(28), dp(28))); tabViews.add(tab);
        tab.setOnFocusChangeListener((v, focus) -> { if (focus) select(key); });
        tab.setOnClickListener(v -> {
            select(key);
            View first = body.findViewWithTag("firstControl");
            if (first != null) first.requestFocus();
        });
    }

    private void select(String page) {
        if (page.equals(selected)) return;
        selected = page;
        for (View tab : tabViews) tab.setSelected(page.equals(tab.getTag()));
        overviewNetwork = null; networkStatus = null; vpnStatus = null;
        body.removeAllViews();
        LinearLayout column = column(); body.addView(column, new FrameLayout.LayoutParams(-1, -2));
        if ("inputs".equals(page)) {
            host.controlInputRows(column, popup);
            for (int i = 0; i < column.getChildCount(); i++) {
                View row = column.getChildAt(i);
                if (row.findViewById(R.id.title) == null) continue;
                row.getLayoutParams().height = dp(44);
                row.setBackground(material(row, 18));
            }
            if (column.getChildCount() > 0) column.getChildAt(0).setTag("firstControl");
            return;
        }
        if ("network".equals(page)) {
            networkStatus = info(column, R.drawable.ic_wifi, R.string.quick_network);
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
        right.addView(network, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout settings = tile(R.drawable.ic_settings, host.getString(R.string.settings), false,
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
        boolean online = false;
        if (connectivity != null) for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                transport = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "Wi-Fi";
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                break;
            }
        }
        if (overviewNetwork != null) overviewNetwork.setText(transport);
        if (networkStatus != null) networkStatus.setText(host.getString(R.string.cc_network_status, transport, host.getString(online ? R.string.cc_online : R.string.cc_no_internet)));
        if (vpnStatus != null) vpnStatus.setText(host.getString(hasVpn() ? R.string.cc_vpn_connected : R.string.cc_vpn_disconnected));
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
        tile.addView(image, new LinearLayout.LayoutParams(dp(large ? 42 : 20), dp(large ? 42 : 20)));
        TextView title = text(label, large ? 12 : 11, 0xe6ffffff);
        title.setGravity(Gravity.CENTER); title.setMaxLines(large ? 2 : 1); title.setEllipsize(TextUtils.TruncateAt.END);
        if (!large) title.setAutoSizeTextTypeUniformWithConfiguration(9, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(large ? -1 : -2, -2);
        if (large) titleLp.topMargin = dp(8); else titleLp.setMarginStart(dp(5));
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
        parent.addView(slot, new LinearLayout.LayoutParams(0, -1, 1));
        ImageView button = new ImageView(host); button.setImageResource(icon);
        button.setContentDescription(host.getString(label));
        button.setImageTintList(android.content.res.ColorStateList.valueOf(0xe6ffffff));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setFocusable(true); button.setClickable(true); button.setId(View.generateViewId());
        button.setBackground(material(button, 24));
        slot.addView(button, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        button.setOnClickListener(v -> action.run());
    }

    private Drawable material(View tile, float radius) {
        return new ControlBackdrop(tile, backdrop, density, dp(radius), backdropScale,
                tile == panel ? 0xa5222029 : 0x70322e36);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(height)); lp.topMargin = dp(6); parent.addView(child, lp);
    }
    private int dp(float value) { return Math.round(value * density); }
}
