package com.gathin.tvhome;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Visible network-tab observer. No UI polling, retained Activity, Wi-Fi lock or saved credentials. */
final class WifiScanner {
    enum State { READY, NO_WIFI, DISABLED, PERMISSION_REQUIRED, LOCATION_REQUIRED, ERROR }

    static final class Snapshot {
        final State state;
        final List<WifiAccessPoints.Entry> networks;
        final String connectedSsid;

        Snapshot(State state, List<WifiAccessPoints.Entry> networks, String connectedSsid) {
            this.state = state;
            this.networks = networks;
            this.connectedSsid = connectedSsid;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Snapshot snapshot && state == snapshot.state
                    && connectedSsid.equals(snapshot.connectedSsid) && networks.equals(snapshot.networks);
        }

        @Override public int hashCode() {
            return (state.hashCode() * 31 + networks.hashCode()) * 31 + connectedSsid.hashCode();
        }
    }

    private static final long SCAN_COOLDOWN_MS = 30_000;
    private static final long STALE_SCAN_MS = 45_000;
    private static long lastScanMs = -SCAN_COOLDOWN_MS;
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(0, 1, 2, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "PeachWifi"), (task, executor) -> {
                Runnable obsolete = executor.getQueue().poll();
                if (obsolete instanceof Future<?> future) future.cancel(false);
                if (!executor.isShutdown()) executor.execute(task);
            });

    private final Context context;
    private final WifiManager wifi;
    private final LocationManager location;
    private final Consumer<Snapshot> listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object deliveryLock = new Object();
    private boolean started, registered, refreshing, refreshAgain, scanRequested, staleScanArmed;
    private int generation;
    private FutureTask<Void> work;
    private Snapshot lastSnapshot;

    private final Runnable staleScan = () -> {
        staleScanArmed = false;
        // Passive system scans normally keep postponing this fallback. Some TV firmware does
        // not scan while already connected, so an open picker must eventually refresh itself.
        refresh(true);
    };

    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())
                    && intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) {
                main.removeCallbacks(staleScan);
                staleScanArmed = false;
                if (lastSnapshot != null && lastSnapshot.state == State.READY) armStaleScan();
            }
            refresh(false);
        }
    };

    WifiScanner(Context host, Consumer<Snapshot> listener) {
        context = host.getApplicationContext();
        wifi = context.getSystemService(WifiManager.class);
        location = context.getSystemService(LocationManager.class);
        this.listener = listener;
    }

    /** Lifecycle methods and callbacks are owned by the main thread. */
    void start() {
        if (started) return;
        synchronized (deliveryLock) { started = true; ++generation; }
        lastSnapshot = null;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        try {
            // These are protected system broadcasts; Wi-Fi may send from its own privileged UID.
            if (Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(changes, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(changes, filter);
            registered = true;
        } catch (SecurityException | IllegalStateException unavailable) {
            deliver(new Snapshot(State.ERROR, Collections.emptyList(), ""));
            return;
        }
        refresh(true);
    }

    void stop() {
        if (!started) return;
        synchronized (deliveryLock) {
            started = false;
            ++generation;
            main.removeCallbacksAndMessages(null);
        }
        if (registered) {
            context.unregisterReceiver(changes);
            registered = false;
        }
        if (work != null) { work.cancel(false); IO.remove(work); work = null; }
        refreshing = refreshAgain = scanRequested = staleScanArmed = false;
        lastSnapshot = null;
    }

    void requestScan() { refresh(true); }

    private void refresh(boolean requestScan) {
        if (!started || !registered) return;
        scanRequested |= requestScan;
        if (refreshing) { refreshAgain = true; return; }
        refreshing = true;
        int ticket = generation;
        boolean activeScan = scanRequested;
        scanRequested = false;
        work = new FutureTask<>(() -> complete(ticket, read(activeScan, ticket)), null) {
            @Override protected void done() {
                // FutureTask.done also runs for canceled queued work. It must release the local
                // coalescing flag, while a stopped generation can never reach its old panel.
                if (isCancelled()) complete(ticket, null);
            }
        };
        IO.execute(work);
    }

    private void complete(int ticket, Snapshot completed) {
        synchronized (deliveryLock) {
            if (!started || ticket != generation) return;
            main.post(() -> {
                if (!started || ticket != generation) return;
                work = null;
                refreshing = false;
                if (completed != null) deliver(completed);
                if (refreshAgain) { refreshAgain = false; refresh(false); }
            });
        }
    }

    private Snapshot read(boolean requestScan, int ticket) {
        if (wifi == null) return new Snapshot(State.NO_WIFI, Collections.emptyList(), "");
        try {
            if (!wifi.isWifiEnabled()) return new Snapshot(State.DISABLED, Collections.emptyList(), "");
            boolean locationGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT <= 28
                    && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (!locationGranted)
                return new Snapshot(State.PERMISSION_REQUIRED, Collections.emptyList(), "");
            // Oreo startScan needs only CHANGE_WIFI_STATE, but its getScanResults still gates
            // target >=23 on location mode + a location permission, otherwise returning no rows.
            boolean locationEnabled = Build.VERSION.SDK_INT >= 28
                    ? location != null && location.isLocationEnabled()
                    : Settings.Secure.getInt(context.getContentResolver(),
                            Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
                            != Settings.Secure.LOCATION_MODE_OFF;
            if (!locationEnabled) return new Snapshot(State.LOCATION_REQUIRED, Collections.emptyList(), "");

            List<ScanResult> results = wifi.getScanResults();
            ArrayList<WifiAccessPoints.Observation> observations = new ArrayList<>(results.size() + 1);
            for (ScanResult result : results)
                observations.add(new WifiAccessPoints.Observation(
                        result.SSID, result.BSSID, result.capabilities, result.level));

            WifiInfo info = wifi.getConnectionInfo();
            String ssid = "", bssid = "";
            if (info != null && info.getSupplicantState() == SupplicantState.COMPLETED) {
                String rawSsid = info.getSSID();
                if (rawSsid != null && !WifiManager.UNKNOWN_SSID.equalsIgnoreCase(rawSsid)) {
                    ssid = rawSsid.length() >= 2 && rawSsid.charAt(0) == '"'
                            && rawSsid.charAt(rawSsid.length() - 1) == '"'
                            ? rawSsid.substring(1, rawSsid.length() - 1) : rawSsid;
                }
                String rawBssid = info.getBSSID();
                if (rawBssid != null && !"02:00:00:00:00:00".equals(rawBssid)) bssid = rawBssid;
                boolean represented = false;
                for (WifiAccessPoints.Observation observation : observations) {
                    if (!bssid.isEmpty() ? bssid.equalsIgnoreCase(observation.bssid)
                            : !ssid.isEmpty() && ssid.equals(observation.ssid)) {
                        represented = true;
                        break;
                    }
                }
                if (!ssid.isEmpty() && !represented) {
                    String capabilities = null;
                    if (Build.VERSION.SDK_INT >= 31) {
                        capabilities = switch (info.getCurrentSecurityType()) {
                            case WifiInfo.SECURITY_TYPE_OPEN -> "";
                            case WifiInfo.SECURITY_TYPE_WEP -> "WEP";
                            case WifiInfo.SECURITY_TYPE_PSK -> "PSK";
                            case WifiInfo.SECURITY_TYPE_SAE -> "SAE";
                            case WifiInfo.SECURITY_TYPE_OWE -> "OWE";
                            case WifiInfo.SECURITY_TYPE_EAP -> "EAP";
                            default -> null;
                        };
                    }
                    observations.add(new WifiAccessPoints.Observation(ssid, bssid, capabilities, info.getRssi()));
                }
            }
            Snapshot snapshot = new Snapshot(State.READY, WifiAccessPoints.build(observations, ssid, bssid), ssid);
            // A Binder read already in progress cannot be interrupted, but closing the tab must
            // not turn its now-obsolete result into an additional radio scan.
            synchronized (deliveryLock) {
                if (!started || ticket != generation) return snapshot;
            }
            if (requestScan) {
                boolean allowed;
                synchronized (WifiScanner.class) {
                    long now = SystemClock.elapsedRealtime();
                    allowed = now - lastScanMs >= SCAN_COOLDOWN_MS;
                    if (allowed) lastScanMs = now;
                }
                if (allowed) wifi.startScan(); // false means throttled/unavailable; cached rows remain valid.
            }
            return snapshot;
        } catch (SecurityException permissionChanged) {
            return new Snapshot(State.PERMISSION_REQUIRED, Collections.emptyList(), "");
        } catch (IllegalStateException unavailable) {
            return new Snapshot(State.ERROR, Collections.emptyList(), "");
        }
    }

    private void deliver(Snapshot snapshot) {
        if (snapshot.state == State.READY) armStaleScan();
        else {
            main.removeCallbacks(staleScan);
            staleScanArmed = false;
        }
        if (!snapshot.equals(lastSnapshot)) {
            lastSnapshot = snapshot;
            listener.accept(snapshot);
        }
    }

    private void armStaleScan() {
        if (!started || staleScanArmed) return;
        staleScanArmed = true;
        main.postDelayed(staleScan, STALE_SCAN_MS);
    }
}
