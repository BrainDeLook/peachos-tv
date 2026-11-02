# Wi-Fi in Control Center

The network tab lists nearby Wi-Fi networks, their signal strength, password protection and the current connection. Radios advertising the same SSID and authentication type are combined. Existing rows stay in place during signal updates so remote focus does not jump. Hidden networks and advanced network configuration remain available through **Network settings**.

Selecting an open, WEP or WPA/WPA2 personal network opens the system's connector for that specific SSID. The OS handles saved credentials and requests a password when needed; PeachOS TV does not receive or store passwords. AOSP TV and the verified TCL firmware use `wifi_ssid` and `wifi_security_name`. TCL retains the exported connector without its usual intent filter, so an explicitly verified system component is also supported. Unsupported firmware or authentication modes, including enterprise and WPA3-only networks, fall back to the full system Wi-Fi settings.

## Permissions

Modern Android requires precise location permission **and** the system Location switch for Wi-Fi scan results. Earlier supported releases accept Fine or Coarse permission, but their location-mode restrictions also apply to a modern-targeted application. PeachOS TV does not request location coordinates. If permission or Location is off, the tab explains the requirement and retains a button for system network settings. Existing connectivity is not changed.

## Resource use

- No Wi-Fi observer, scan timer or worker while another tab is selected or Control Center is closed.
- Ethernet takes priority: the tab uses an Ethernet icon and retains its existing status view; no Wi-Fi scanner is started.
- Show cached results first and listen for system scan/connection events. One initial scan is requested; if no successful system scan arrives for 45 seconds, the visible picker requests a refresh. Each successful system scan postpones this fallback, and active requests share a minimum 30-second interval.
- Binder calls use one background worker with a one-job queue. Bursts of events are combined; unchanged snapshots cause no UI update. The idle worker exits after two seconds.
- At most 32 immutable network entries are exposed. No Wi-Fi lock, permanent background service, password storage or third-party scanning library is used.
- The picker fits a whole number of network bubbles into the available height. Up/Down moves the visible window when needed; partially clipped bubbles are never used to hint at more results. Only the current window is attached to the layout, and focus is transferred before an old row is detached.

Android may reject a scan because of throttling or device state. Cached results remain usable; manual **Refresh networks** observes the same budget.

References: [Android Wi-Fi scans and restrictions](https://developer.android.com/develop/connectivity/wifi/wifi-scan), [AOSP TV WifiConnectionActivity](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android12-release/Settings/src/com/android/tv/settings/connectivity/WifiConnectionActivity.java).
