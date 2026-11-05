package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Bitmap;
import android.view.Display;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.Toast;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A temporary accessibility window; the player's Activity stays foreground and receives no Home intent. */
final class OverlayControls implements ControlCenter.Actions {
    static final String KEY = "control_center_over_apps";
    private final HomeKeyService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Cancel/purge the obsolete configuration job on every dismissal. Only its running predecessor
    // and this opening's job can remain; screenshot jobs must run once to release their buffer.
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 2, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), task -> new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "PeachOverlay"));
    private final WindowManager windows;
    private final ConnectivityManager network;
    private FrameLayout stage;
    private ControlCenter panel;
    private Config config;
    private Map<String, Intent> vpnApps;
    private boolean sources;
    private InputRepository inputRepository;
    private InputRepository.Snapshot inputSnapshot;
    private NavigationSound sound;
    private volatile int generation;
    private long openingStartedAt;
    private int previousEvents;
    private volatile boolean pending;
    private boolean watchingNetwork, closed;
    private Future<?> configurationWork;
    private Runnable captureTimeout;
    private int dismissKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private long dismissDownTime;
    private final Runnable releaseSound = () -> { if (sound != null) { sound.close(); sound = null; } };
    private final Runnable refreshNetwork = () -> { if (panel != null) panel.refreshNetwork(); };
    private final ConnectivityManager.NetworkCallback networkChanges = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network ignored) { refresh(); }
        @Override public void onLost(Network ignored) { refresh(); }
        @Override public void onCapabilitiesChanged(Network ignored, android.net.NetworkCapabilities capabilities) { refresh(); }
        private void refresh() {
            main.removeCallbacks(refreshNetwork);
            main.post(refreshNetwork);
        }
    };

    OverlayControls(HomeKeyService service) {
        this.service = service;
        windows = service.getSystemService(WindowManager.class);
        network = service.getSystemService(ConnectivityManager.class);
    }

    void held() {
        if (closed) return;
        if (panel != null) { settingsGearHeld(); return; }
        if (pending || stage != null) return;
        pending = true;
        openingStartedAt = android.os.SystemClock.uptimeMillis();
        int ticket = ++generation;
        configurationWork = worker.submit(() -> {
            // This job crosses storage and several system-service boundaries. Future would
            // otherwise hide an API failure and leave this opening pending indefinitely.
            try {
                Config current = Config.load(service);
                if (!pending || ticket != generation) return;
                Map<String, Intent> currentVpn = ControlCenter.discoverVpnApps(service, current.vpnApp);
                if (!pending || ticket != generation) return;
                InputRepository currentInputs = new InputRepository(service, current);
                InputRepository.Snapshot snapshot = currentInputs.read();
                boolean currentSources = snapshot.available();
                main.post(() -> {
                    if (!pending || ticket != generation) return;
                    configurationWork = null;
                    config = current; vpnApps = currentVpn; sources = currentSources;
                    inputRepository = currentInputs; inputSnapshot = snapshot;
                    android.util.Log.d("PeachOverlay", "Config prepared in "
                            + (android.os.SystemClock.uptimeMillis() - openingStartedAt) + "ms");
                    if (Build.VERSION.SDK_INT >= 30 && service.getSharedPreferences(Config.SETTINGS_NAME, 0).getBoolean("liquid_glass", true)) capture(ticket);
                    else attach(ticket, null);
                });
            } catch (RuntimeException unavailable) {
                android.util.Log.w("PeachOverlay", "Control preparation unavailable", unavailable);
                main.post(() -> {
                    if (!pending || ticket != generation) return;
                    cancel();
                    Toast.makeText(service, R.string.not_available, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @androidx.annotation.RequiresApi(30)
    private void capture(int ticket) {
        captureTimeout = () -> attach(ticket, null);
        main.postDelayed(captureTimeout, 500);
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, main::post, new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                if (!pending || ticket != generation) { screenshot.getHardwareBuffer().close(); return; }
                android.util.Log.d("PeachOverlay", "Snapshot received after "
                        + (android.os.SystemClock.uptimeMillis() - openingStartedAt) + "ms");
                worker.execute(() -> {
                    // A canceled queued screenshot still owns its hardware buffer. Release it here,
                    // without doing GPU readback or blur for a panel that will never be shown.
                    if (!pending || ticket != generation) { screenshot.getHardwareBuffer().close(); return; }
                    Bitmap bitmap = null;
                    try {
                        bitmap = ControlScreenshot.small(screenshot);
                        if (bitmap != null && pending && ticket == generation) ControlBackdrop.blur(bitmap);
                    } catch (RuntimeException unsupportedRenderer) {
                        if (bitmap != null) bitmap.recycle();
                        bitmap = null;
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
        } catch (SecurityException | IllegalStateException disconnected) {
            android.util.Log.i("PeachOverlay", "Screenshot service unavailable", disconnected);
            attach(ticket, null);
        }
    }

    private void attach(int ticket, Bitmap backdrop) {
        if (!pending || ticket != generation) { if (backdrop != null) backdrop.recycle(); return; }
        pending = false;
        android.util.Log.d("PeachOverlay", "Backdrop ready after "
                + (android.os.SystemClock.uptimeMillis() - openingStartedAt) + "ms");
        if (captureTimeout != null) { main.removeCallbacks(captureTimeout); captureTimeout = null; }
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
        catch (WindowManager.BadTokenException | IllegalStateException | SecurityException unavailable) {
            stage = null;
            if (backdrop != null) backdrop.recycle();
            main.post(releaseSound);
            Toast.makeText(service, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        android.accessibilityservice.AccessibilityServiceInfo info = service.getServiceInfo();
        if (info == null) {
            removeWindow();
            if (backdrop != null) backdrop.recycle();
            return;
        }
        previousEvents = info.eventTypes;
        info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED; service.setServiceInfo(info);
        if (network != null) {
            try {
                network.registerDefaultNetworkCallback(networkChanges, main);
                watchingNetwork = true;
            } catch (RuntimeException unavailable) {
                // A firmware permission/quota failure must not prevent all other controls opening.
                android.util.Log.w("PeachOverlay", "Network updates unavailable", unavailable);
            }
        }
        stage.getViewTreeObserver().addOnGlobalFocusChangeListener((before, after) -> {
            if (before != null && after != null && after.isFocusable() && sound != null) sound.play();
        });
        // Populate the window in this turn, before its first traversal. Posting this work used
        // to present an empty full-screen window and then require a second layout for controls.
        panel = new ControlCenter(service, this, stage, home, gear, capsule, clock, null, backdrop,
                sources, config.animations, config.reducedMotion, config.vpnApp, vpnApps);
        panel.show();
        android.util.Log.d("PeachOverlay", "Controls constructed after "
                + (android.os.SystemClock.uptimeMillis() - openingStartedAt) + "ms");
    }

    int baseEvents(int events) {
        if (stage == null) return events;
        previousEvents = events;
        return events | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
    }

    boolean handle(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_HOME && code != KeyEvent.KEYCODE_ALL_APPS
                && code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_ESCAPE) return false;
        boolean startedHere = code == dismissKeyCode && event.getDownTime() == dismissDownTime;
        if (stage == null && !pending && !startedHere) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            dismissKeyCode = code; dismissDownTime = event.getDownTime();
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            dismissKeyCode = KeyEvent.KEYCODE_UNKNOWN;
            if (event.isCanceled()) return true;
            if (panel != null) panel.close();
            else cancel();
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
        if (panel != center) return;
        panel = null;
        removeWindow();
    }
    private void removeWindow() {
        if (stage == null) return;
        FrameLayout removed = stage; stage = null;
        try { windows.removeViewImmediate(removed); }
        catch (RuntimeException unavailable) { android.util.Log.w("PeachOverlay", "Overlay window already unavailable", unavailable); }
        if (watchingNetwork) {
            watchingNetwork = false;
            try { network.unregisterNetworkCallback(networkChanges); }
            catch (RuntimeException unavailable) { android.util.Log.w("PeachOverlay", "Network callback already unavailable", unavailable); }
        }
        main.removeCallbacks(refreshNetwork);
        android.accessibilityservice.AccessibilityServiceInfo info = service.getServiceInfo();
        if (info != null) { info.eventTypes = previousEvents; service.setServiceInfo(info); }
        main.removeCallbacks(releaseSound); main.postDelayed(releaseSound, 600);
    }
    @Override public void controlOpen(Intent intent) {
        if (sound != null) sound.playOpen();
        if (!SystemSettings.start(service, intent)) Toast.makeText(service, R.string.not_available, Toast.LENGTH_SHORT).show();
    }
    @Override public void controlSleep() {
        if (!SystemSleep.sleep()) Toast.makeText(service, R.string.cc_sleep_unavailable, Toast.LENGTH_LONG).show();
    }

    @Override public void controlInputRows(LinearLayout rows, java.util.function.Consumer<Runnable> activate) {
        inputRepository.populate(inputSnapshot, rows, activate, () -> { if (sound != null) sound.playSourceOpen(); });
    }
    private void cancel() {
        generation++; pending = false;
        if (captureTimeout != null) { main.removeCallbacks(captureTimeout); captureTimeout = null; }
        if (configurationWork != null) {
            configurationWork.cancel(false);
            worker.purge();
            configurationWork = null;
        }
        if (panel != null) panel.dismissImmediately();
        else removeWindow();
    }
    void close() {
        if (closed) return;
        closed = true;
        cancel();
        main.removeCallbacks(refreshNetwork);
        main.removeCallbacks(releaseSound);
        // Keep queued screenshot/result callbacks: their stale-request branch releases the
        // hardware buffer or tiny bitmap. Removing every Handler message would skip that cleanup.
        worker.shutdown();
        if (sound != null) { sound.close(); sound = null; }
    }
}
