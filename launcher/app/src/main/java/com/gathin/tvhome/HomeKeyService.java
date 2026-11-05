package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.SystemClock;
import android.os.UserManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * With stock_home_redirect set, it also sends the stock launcher's home screen back here, for firmware that turns
 * that launcher on again after it was disabled (XGIMI's xrmservice does, on its own remote keys), or that cannot be
 * disabled at all, and a short press of Home opens this launcher even when the stock home is already on screen.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    private static volatile HomeKeyService connectedService;
    private Config appliedConfig;
    private boolean appliedKeyFilter, serviceInfoApplied;
    private SharedPreferences settingsChoices;
    private String stockHomePackage = "";
    private volatile String appliedSetupPackage = "";
    private final InstallerProbe installerProbe = new InstallerProbe();

    static boolean isBound() {
        return connectedService != null;
    }

    static String appliedStockPackage() {
        HomeKeyService service = connectedService;
        return service == null ? "" : service.appliedSetupPackage;
    }

    static boolean keyFilterActive() {
        HomeKeyService service = connectedService;
        AccessibilityServiceInfo info = service == null ? null : service.getServiceInfo();
        return info != null && (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0;
    }

    static boolean sleep() {
        HomeKeyService service = connectedService;
        if (Build.VERSION.SDK_INT < 28 || service == null) return false;
        try { return service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN); }
        catch (RuntimeException unavailable) {
            android.util.Log.w("PeachRemote", "Standby action unavailable", unavailable);
            return false;
        }
    }

    static boolean configurationReady() {
        return connectedService != null && connectedService.appliedConfig != null && connectedService.serviceInfoApplied;
    }

    static Set<String> serviceUseReasons(Context context) {
        Set<String> reasons = new HashSet<>();
        HomeKeyService service = connectedService;
        SharedPreferences choices = context.getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        if (choices.getBoolean(SettingsButton.KEY, false)) reasons.add("settings");
        if (SettingsKeyBinding.isLearning()) reasons.add("learning");
        if (!HomeSetupState.read(context).stockPackage.isEmpty()) reasons.add("home");
        if (service != null && service.appliedConfig != null) {
            if (!service.appliedConfig.stockHomeRedirect.isEmpty()) { reasons.add("home"); reasons.add("remaps"); }
            if (!service.appKeys.isEmpty() || !service.appScancodes.isEmpty()
                    || !service.holdAppKeys.isEmpty() || !service.holdAppScancodes.isEmpty()
                    || service.settingsKeycodes.length > 0 || service.settingsScancodes.length > 0
                    || service.consumeVolumeKeys) reasons.add("remaps");
        }
        return reasons;
    }

    enum RemoteReadiness { SERVICE_OFF, CONFIG_LOADING, FILTER_DISABLED, PASSTHROUGH, READY }

    static RemoteReadiness remoteReadiness() {
        HomeKeyService service = connectedService;
        if (service == null) return RemoteReadiness.SERVICE_OFF;
        if (service.appliedConfig == null || !service.serviceInfoApplied) return RemoteReadiness.CONFIG_LOADING;
        if (!service.appliedConfig.keyFilter) return RemoteReadiness.FILTER_DISABLED;
        return service.gamepads != null && service.gamepads.isPassingThrough()
                ? RemoteReadiness.PASSTHROUGH : RemoteReadiness.READY;
    }

    private final RemoteEventCoordinator.Client remoteClient = new RemoteEventCoordinator.Client() {
        @Override public boolean filtering() { return connectedService == HomeKeyService.this && appliedConfig != null && appliedKeyFilter
                && (gamepads == null || !gamepads.isPassingThrough()); }
        @Override public boolean handle(KeyEvent event) { return handleRemoteEvent(event); }
        @Override public void cancelGestures() { if (settingsButton != null) settingsButton.cancel(); handler.removeCallbacks(redirectHome); }
    };

    /** Receiver writes are applied on the helper's main thread without another full theme scan. */
    static void refreshHomeSetup() {
        HomeKeyService service = connectedService;
        if (service == null) return;
        service.handler.post(() -> {
            if (connectedService == service && service.appliedConfig != null)
                service.applyHomeSetup(service.appliedConfig);
        });
    }

    /** restore_key_services was on when the config was read: this helper is noted for ServiceRepair. */
    private boolean repairRecord;
    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    private SettingsButton settingsButton;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChoiceChanged = (preferences, key) -> {
        if ((key == null || SettingsButton.KEY.equals(key)) && appliedConfig != null) {
            if (settingsButton != null) settingsButton.cancel();
            applyHomeSetup(appliedConfig);
        }
    };
    private OverlayControls overlayControls;
    /** consume_volume_keys: Volume Up/Down go to VolumeKeys instead of on to the firmware. */
    private boolean consumeVolumeKeys;
    /** app_keys, the same maps InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();

    /** Waits for the user to unlock when Android binds this service at boot; null once the config is read. */
    private BroadcastReceiver unlockReceiver;
    /** The stock launcher's HOME activities (stock_home_redirect), disabled ones included. */
    private final Set<String> stockHome = new HashSet<>();
    /** Stops key filtering while a game controller is connected; null unless gamepad_passthrough and key_filter, or before the unlock. */
    private GamepadPassthrough gamepads;
    private ConfigRefresh.Request configurationRefresh;
    private String appliedThemeKey;
    private Map<String, ?> appliedPreferences;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        stopHelpers();
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        overlayControls = new OverlayControls(this);
        settingsButton = new SettingsButton(() -> {
            if (MainActivity.showForegroundControls()) return;
            if (getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getBoolean(OverlayControls.KEY, false))
                overlayControls.held();
            // Use the HOME entry, not a custom-action Activity task beside the existing Home.
            else SystemSettings.start(this, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName()).putExtra(MainActivity.EXTRA_CONTROL_CENTER, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        connectedService = this;
        RemoteEventCoordinator.connect(this, RemoteEventCoordinator.HOME, remoteClient);
        // The service is directBootAware: Android 11 skips a service that is not while the user is still
        // locked at boot, writes accessibility_enabled=0 and, on TCL G03 firmware, never binds it after the
        // unlock. Config.load reads credential-protected storage, so wait for the unlock before reading it.
        UserManager users = getSystemService(UserManager.class);
        if (users.isUserUnlocked()) {
            loadConfig();
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaitingForUnlock();
                loadConfig();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // The unlock may have happened between the check and the registration.
        if (users.isUserUnlocked()) {
            stopWaitingForUnlock();
            loadConfig();
        }
    }

    private void stopWaitingForUnlock() {
        if (unlockReceiver == null) return;
        unregisterReceiver(unlockReceiver);
        unlockReceiver = null;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // The in-app controller can run without any launcher Activity receiving memory callbacks.
        if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN)
            ControlCenter.trimBackdropCache();
    }

    @Override
    public void onDestroy() {
        stopHelpers();
        super.onDestroy();
    }

    private void stopHelpers() {
        RemoteEventCoordinator.disconnect(RemoteEventCoordinator.HOME, remoteClient);
        SettingsKeyBinding.abort();
        if (connectedService == this) connectedService = null;
        appliedConfig = null; appliedKeyFilter = false; serviceInfoApplied = false;
        appliedSetupPackage = "";
        installerProbe.reset();
        stockHomePackage = "";
        stockHome.clear();
        if (settingsChoices != null) {
            settingsChoices.unregisterOnSharedPreferenceChangeListener(settingsChoiceChanged);
            settingsChoices = null;
        }
        if (configurationRefresh != null) { configurationRefresh.cancel(); configurationRefresh = null; }
        appliedThemeKey = null; appliedPreferences = null;
        if (overlayControls != null) { overlayControls.close(); overlayControls = null; }
        if (settingsButton != null) { settingsButton.cancel(); settingsButton = null; }
        handler.removeCallbacksAndMessages(null);
        stopWaitingForUnlock();
        if (gamepads != null) { gamepads.stop(); gamepads = null; }
        if (consumeVolumeKeys) VolumeKeys.stop();
        consumeVolumeKeys = false;
    }

    private void loadConfig() {
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        if (settingsChoices == null) {
            settingsChoices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
            settingsChoices.registerOnSharedPreferenceChangeListener(settingsChoiceChanged);
        }
        long since = SystemClock.uptimeMillis();
        Config cached = Config.loadCached(this);
        if (cached != null) applyConfig(cached);
        configurationRefresh = ConfigRefresh.request(this, since, fresh -> {
            if (settingsButton != null && (fresh.themeVerified && ThemeSnapshot.isCurrent(fresh.themeSnapshot)
                    || appliedPreferences == null)) applyConfig(fresh);
        });
    }

    private void applyConfig(Config config) {
        String key = config.themeSnapshot == null ? null : config.themeSnapshot.key;
        Map<String, ?> preferences = Config.visualPreferences(this);
        if (key != null && key.equals(appliedThemeKey) && preferences.equals(appliedPreferences)) return;
        appliedThemeKey = key; appliedPreferences = preferences;
        appliedConfig = config;
        // restore_key_services: note that this helper runs, so Home can put it back after a force stop. Here, not
        // in onServiceConnected: the note is in credential-protected storage too, readable only after the unlock.
        repairRecord = config.restoreKeyServices;
        if (repairRecord) ServiceRepair.connected(this, getClass());
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        if (consumeVolumeKeys && !config.consumeVolumeKeys) VolumeKeys.stop();
        consumeVolumeKeys = config.consumeVolumeKeys;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
        applyHomeSetup(config);
    }

    private void applyHomeSetup(Config config) {
        HomeSetupState setup = HomeSetupState.read(this);
        // An empty installer override restores all theme behavior, including key filtering.
        boolean override = !setup.stockPackage.isEmpty();
        String redirect = override ? setup.stockPackage : config.stockHomeRedirect;
        boolean remoteBindings = !appKeys.isEmpty() || !appScancodes.isEmpty()
                || !holdAppKeys.isEmpty() || !holdAppScancodes.isEmpty()
                || settingsKeycodes.length > 0 || settingsScancodes.length > 0 || config.consumeVolumeKeys;
        boolean keyFilter = setup.keyFilterEnabled(config.keyFilter,
                settingsChoices.getBoolean(SettingsButton.KEY, false) || SettingsKeyBinding.isLearning(), remoteBindings);
        if (!keyFilter) { VolumeKeys.stop(); if (settingsButton != null) settingsButton.cancel(); }
        android.util.Log.i("PeachRemote", "Apply remote configuration: filter=" + config.keyFilter
                + " requested=" + keyFilter + " learning=" + SettingsKeyBinding.isLearning());
        // Reapplying the filter must not override an already connected game controller's passthrough.
        if (gamepads != null) { gamepads.stop(); gamepads = null; }
        appliedSetupPackage = "";
        stockHome.clear();
        stockHomePackage = redirect;
        if (!redirect.isEmpty()) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(redirect);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                stockHome.add(ri.activityInfo.name);
                // A HOME alias reports its actual Activity class in window events.
                if (ri.activityInfo.targetActivity != null) stockHome.add(ri.activityInfo.targetActivity);
            }
        }
        // home_key_service.xml subscribes to no events, so the redirect asks for window changes only when it is on.
        AccessibilityServiceInfo info = getServiceInfo();
        serviceInfoApplied = false;
        if (info != null) {
            // A rebound service can retain its previous runtime subscription even when the new
            // config no longer redirects stock Home. OverlayControls adds this event temporarily.
            int baseEvents = stockHome.isEmpty() ? 0 : AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.eventTypes = overlayControls == null ? baseEvents : overlayControls.baseEvents(baseEvents);
            if (keyFilter) info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            else info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
            appliedKeyFilter = keyFilter;
            serviceInfoApplied = true;
            if (override && !stockHome.isEmpty()) appliedSetupPackage = setup.stockPackage;
        }
        // At a late bind the stock Home may already be foreground and emit no new window event.
        // Probe once per installer target/bind, never on every settings or verified-theme refresh.
        if (installerProbe.applied(appliedSetupPackage)) probeHomeWindow();
        else if (!override && !stockHome.isEmpty()) probeAtBoot();
        // gamepad_passthrough, off by default. Only with key_filter on: off, the service never filters keys.
        if (config.gamepadPassthrough && keyFilter) {
            gamepads = new GamepadPassthrough(this);
            gamepads.start();
        }
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        return RemoteEventCoordinator.dispatch(event);
    }

    private boolean handleRemoteEvent(KeyEvent event) {
        if (overlayControls != null && overlayControls.handle(event)) return true;
        if (SettingsKeyBinding.capture(event)) return true;
        if (SettingsKeyBinding.matches(event, getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE))) {
            settingsButton.handle(event);
            return true;
        }
        if (consumeVolumeKeys && VolumeKeys.handle(this, event)) return true;
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, appScancodes.get(event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getBoolean(SettingsButton.KEY, false)) {
                settingsButton.handle(event);
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : SystemSettings.intent(this, Settings.ACTION_SETTINGS);
                try {
                    startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        if (RemoteKeys.handle(this, event, appKeys.get(event.getKeyCode()),
                holdAppKeys.get(event.getKeyCode()))) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && !stockHome.isEmpty()) {
            redirectHomeKey(event);
            return false;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            SystemSettings.start(this, home);
        }
        return true;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        // A disconnected service may remain alive or reconnect before onDestroy. Nothing from its
        // previous configuration may keep filtering keys, repeating volume, or waiting for unlock.
        stopHelpers();
        if (repairRecord) ServiceRepair.disconnected(this, getClass());
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (overlayControls != null) overlayControls.windowChanged(event);
        // stock_home_redirect: the firmware turned the stock launcher back on and its home screen came up.
        if (stockHome.isEmpty() || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg != null && stockHomePackage.contentEquals(pkg)
                && cls != null && stockHome.contains(cls.toString())) openHome();
    }

    /** How long after boot the first bind still counts as "at boot" for probeAtBoot. */
    static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;

    /** stock_home_redirect at boot: the stock home is often on screen before Android binds this service, so no
     *  window event for it arrives (#1116). Once per boot, open ProbeActivity: when it closes, whatever was in
     *  front comes back and sends a window event, and onAccessibilityEvent covers it only if it is the stock home.
     *  An HDMI input or an app that the TV resumed at boot is left alone. */
    private void probeAtBoot() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = getSharedPreferences("boot", MODE_PRIVATE);
        if (!firstBindAfterBoot(boot, prefs.getInt("probe_boot", -1), SystemClock.elapsedRealtime())) return;
        prefs.edit().putInt("probe_boot", boot).apply();
        probeHomeWindow();
    }

    private void probeHomeWindow() {
        try {
            startActivity(new Intent(this, ProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    /** One probe for a newly applied installer target, independent of boot uptime. */
    static final class InstallerProbe {
        private String lastPackage = "";
        boolean applied(String stockPackage) {
            boolean changed = !lastPackage.equals(stockPackage);
            lastPackage = stockPackage;
            return changed && !stockPackage.isEmpty();
        }
        void reset() { lastPackage = ""; }
    }

    static boolean firstBindAfterBoot(int boot, int lastBoot, long uptimeMs) {
        return boot >= 0 && boot != lastBoot && uptimeMs < BOOT_WINDOW_MS;
    }

    /** Lets the system see the key, so a long press still does what the firmware gives it. */
    private static final long HOME_KEY_DELAY_MS = 250;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable redirectHome = this::openHome;

    /** stock_home_redirect on the Home key: when the stock home is already on screen (it came up at boot, before
     *  Android bound this service), Home brings it up again with no window change, so the redirect above never
     *  fires and the TV stays on the stock home until another app opens (#1116). A short press opens this
     *  launcher shortly after the system has handled the key; a long press (Google TV's quick settings) is left
     *  alone. Held time, not repeats: key repeats are made after accessibility filtering, so none arrive here. */
    private void redirectHomeKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()
                && isShortPress(event.getDownTime(), event.getEventTime(), ViewConfiguration.getLongPressTimeout())) {
            handler.removeCallbacks(redirectHome);
            handler.postDelayed(redirectHome, HOME_KEY_DELAY_MS);
        }
    }

    static boolean isShortPress(long downTime, long upTime, long longPressTimeout) {
        return upTime - downTime < longPressTimeout;
    }

    private void openHome() {
        // The stock Home window event can beat the delayed key fallback. Handle that same press
        // once, without a second HOME intent resetting focus or replaying the transition later.
        handler.removeCallbacks(redirectHome);
        SystemSettings.start(this, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    @Override
    public void onInterrupt() {
    }
}
