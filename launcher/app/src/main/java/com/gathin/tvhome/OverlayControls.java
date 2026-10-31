package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Bitmap;
import android.view.Display;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A temporary accessibility window; the player's Activity stays foreground and receives no Home intent. */
final class OverlayControls implements ControlCenter.Actions {
    static final String KEY = "control_center_over_apps";
    private final HomeKeyService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 2, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1), task -> new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "PeachOverlay"));
    private final WindowManager windows;
    private final ConnectivityManager network;
    private FrameLayout stage;
    private ControlCenter panel;
    private Config config;
    private NavigationSound sound;
    private int generation, previousEvents;
    private boolean pending, watchingNetwork;
    private final Runnable releaseSound = () -> { if (sound != null) { sound.close(); sound = null; } };
    private final ConnectivityManager.NetworkCallback networkChanges = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network ignored) { refresh(); }
        @Override public void onLost(Network ignored) { refresh(); }
        @Override public void onCapabilitiesChanged(Network ignored, android.net.NetworkCapabilities capabilities) { refresh(); }
        private void refresh() { main.post(() -> { if (panel != null) panel.refreshNetwork(); }); }
    };

    OverlayControls(HomeKeyService service) {
        this.service = service;
        windows = service.getSystemService(WindowManager.class);
        network = service.getSystemService(ConnectivityManager.class);
    }

    void held() {
        if (panel != null) { settingsGearHeld(); return; }
        if (pending) return;
        pending = true;
        int ticket = ++generation;
        worker.execute(() -> {
            Config current = Config.load(service);
            main.post(() -> {
                if (!pending || ticket != generation) return;
                config = current;
                if (Build.VERSION.SDK_INT >= 30 && service.getSharedPreferences(Config.SETTINGS_NAME, 0).getBoolean("liquid_glass", true)) capture(ticket);
                else attach(ticket, null);
            });
        });
    }

    @androidx.annotation.RequiresApi(30)
    private void capture(int ticket) {
        main.postDelayed(() -> attach(ticket, null), 500);
        service.takeScreenshot(Display.DEFAULT_DISPLAY, main::post, new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                if (!pending || ticket != generation) { screenshot.getHardwareBuffer().close(); return; }
                worker.execute(() -> {
                    Bitmap bitmap = null;
                    try {
                        bitmap = ControlScreenshot.small(screenshot);
                        if (bitmap != null) ControlBackdrop.blur(bitmap);
                    } catch (RuntimeException unsupportedRenderer) {
                        android.util.Log.w("PeachOverlay", "Snapshot unavailable", unsupportedRenderer);
                    }
                    Bitmap result = bitmap;
                    main.post(() -> attach(ticket, result));
                });
            }
            @Override public void onFailure(int error) {
                android.util.Log.i("PeachOverlay", "Screenshot unavailable: " + error);
                attach(ticket, null);
            }
        });
    }

    private void attach(int ticket, Bitmap backdrop) {
        if (!pending || ticket != generation) { if (backdrop != null) backdrop.recycle(); return; }
        pending = false;
        main.removeCallbacks(releaseSound);
        if (sound == null) sound = new NavigationSound(service);
        stage = new FrameLayout(service);
        stage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout home = new FrameLayout(service); home.setVisibility(View.INVISIBLE);
        stage.addView(home, new FrameLayout.LayoutParams(-1, -1));
        TextClock clock = new TextClock(service); clock.setTextSize(12.5f); clock.setTextColor(0xbfffffff);
        clock.setTypeface(service.getResources().getFont(R.font.inter_medium)); clock.setFontFeatureSettings("tnum");
        ImageView gear = new ImageView(service);
        LinearLayout capsule = new LinearLayout(service); capsule.addView(clock); capsule.addView(gear);
        home.addView(capsule);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        params.setTitle("PeachOS TV · Control Center");
        try { windows.addView(stage, params); }
        catch (WindowManager.BadTokenException | IllegalStateException unavailable) {
            stage = null;
            if (backdrop != null) backdrop.recycle();
            main.post(releaseSound);
            Toast.makeText(service, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        android.accessibilityservice.AccessibilityServiceInfo info = service.getServiceInfo();
        previousEvents = info.eventTypes;
        info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED; service.setServiceInfo(info);
        if (network != null) { network.registerDefaultNetworkCallback(networkChanges); watchingNetwork = true; }
        stage.getViewTreeObserver().addOnGlobalFocusChangeListener((before, after) -> {
            if (before != null && after != null && after.isFocusable() && sound != null) sound.play();
        });
        stage.post(() -> {
            if (stage == null || ticket != generation) { if (backdrop != null) backdrop.recycle(); return; }
            TvInputManager inputs = service.getSystemService(TvInputManager.class);
            boolean sources = !config.sourcePanelService.isEmpty() || config.coocaaSources
                    || inputs != null && inputs.getTvInputList().stream().anyMatch(i -> i.isPassthroughInput() && !i.isHidden(service));
            panel = new ControlCenter(service, this, stage, home, gear, capsule, clock, null, backdrop,
                    sources, config.animations, config.reducedMotion, config.vpnApp);
            panel.show();
        });
    }

    boolean handle(KeyEvent event) {
        if (stage == null && !pending) return false;
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME && event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            if (panel != null) panel.closeThen(() -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME));
            else { cancel(); service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); }
        }
        return true;
    }

    void windowChanged(AccessibilityEvent event) {
        if (panel == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (MainActivity.class.getName().contentEquals(event.getClassName() == null ? "" : event.getClassName())) panel.close();
    }

    @Override public boolean remoteSettingsHoldEnabled() { return true; }
    @Override public boolean controlBackdropReady() { return true; }
    @Override public String controlBackdropKey() { return ""; }
    @Override public void settingsGearHeld() {
        if (service.getSharedPreferences(Config.SETTINGS_NAME, 0)
                .getBoolean(LauncherSettingsActivity.GEAR_HOLD_SETTINGS_KEY, true) && panel != null)
            panel.closeThen(() -> controlOpen(new Intent(service, LauncherSettingsActivity.class)));
    }
    @Override public void controlCenterDismissed(ControlCenter center, boolean returnToGear) {
        if (panel == center) panel = null;
        removeWindow();
    }
    private void removeWindow() {
        if (stage == null) return;
        windows.removeViewImmediate(stage); stage = null;
        if (watchingNetwork) { network.unregisterNetworkCallback(networkChanges); watchingNetwork = false; }
        android.accessibilityservice.AccessibilityServiceInfo info = service.getServiceInfo();
        if (info != null) { info.eventTypes = previousEvents; service.setServiceInfo(info); }
        main.removeCallbacks(releaseSound); main.postDelayed(releaseSound, 600);
    }
    @Override public void controlOpen(Intent intent) {
        if (sound != null) sound.playOpen();
        try { service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(service, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
    @Override public void controlSleep() {
        if (Build.VERSION.SDK_INT < 28 || !service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN))
            Toast.makeText(service, R.string.cc_sleep_unavailable, Toast.LENGTH_LONG).show();
    }

    @Override public void controlInputRows(LinearLayout rows, java.util.function.Consumer<Runnable> activate) {
        TvInputManager manager = service.getSystemService(TvInputManager.class);
        List<TvInputInfo> all = manager == null ? new ArrayList<>() : manager.getTvInputList();
        List<TvInputInfo> inputs = new ArrayList<>();
        for (TvInputInfo input : all) if (input.isPassthroughInput() && !input.isHidden(service)
                && !Integer.valueOf(-1).equals(InputLaunch.mappedSource(service, input.getId()))) inputs.add(input);
        // Prefer CEC children over their port, as on Home; custom port names remain visible otherwise.
        java.util.Set<String> parents = new java.util.HashSet<>();
        for (TvInputInfo input : inputs) if (input.getParentId() != null) parents.add(input.getParentId());
        inputs.removeIf(input -> parents.contains(input.getId()));
        inputs.sort(java.util.Comparator.comparing(input -> String.valueOf(input.loadLabel(service))));
        for (TvInputInfo input : inputs) {
            CharSequence label = input.loadCustomLabel(service);
            if (label == null || label.length() == 0) label = input.loadLabel(service);
            boolean hdmi = input.getType() == TvInputInfo.TYPE_HDMI;
            int state = hdmi ? manager.getInputState(input.getId()) : -1;
            View row = LayoutInflater.from(service).inflate(R.layout.item_input, rows, false);
            ((TextView) row.findViewById(R.id.title)).setText(label);
            ((ImageView) row.findViewById(R.id.icon)).setImageResource(hdmi ? R.drawable.ic_input : R.drawable.ic_av);
            row.findViewById(R.id.status).setVisibility(View.GONE);
            row.findViewById(R.id.dot).setVisibility(state == TvInputManager.INPUT_STATE_CONNECTED ? View.VISIBLE : View.INVISIBLE);
            row.setOnClickListener(v -> activate.accept(() -> {
                if (sound != null) sound.playSourceOpen();
                InputLaunch.openInput(service, input.getId(), config.inputAction, config.inputUriScheme, config.liveTvPackage);
            }));
            rows.addView(row);
        }
        if (rows.getChildCount() == 0) {
            TextView fallback = new TextView(service); fallback.setText(R.string.inputs);
            fallback.setId(R.id.title);
            fallback.setTextColor(0xe6ffffff); fallback.setGravity(android.view.Gravity.CENTER);
            fallback.setFocusable(true); fallback.setClickable(true);
            fallback.setOnClickListener(v -> activate.accept(() -> controlOpen(new Intent(service, MainActivity.class)
                    .setAction(MainActivity.ACTION_VIEW_INPUTS))));
            rows.addView(fallback, new LinearLayout.LayoutParams(-1, dp(44)));
        }
    }
    private int dp(int value) { return Math.round(value * service.getResources().getDisplayMetrics().density); }
    private void cancel() {
        generation++; pending = false;
        if (panel != null) panel.dismissImmediately();
        else removeWindow();
    }
    void close() {
        cancel(); main.removeCallbacksAndMessages(null); worker.shutdownNow();
        if (sound != null) { sound.close(); sound = null; }
    }
}
