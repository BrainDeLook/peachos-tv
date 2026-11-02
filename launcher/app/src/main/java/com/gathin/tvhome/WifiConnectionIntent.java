package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

/** Hand connection to the system: credentials and the device's default route remain OS-owned. */
final class WifiConnectionIntent {
    private WifiConnectionIntent() { }

    static Intent forNetwork(Context context, WifiAccessPoints.Entry network) {
        // AOSP TV's legacy connector handles open/WEP/PSK. Enterprise and WPA3-only
        // need the firmware's full network UI rather than an incomplete password-only config.
        if (network.security.tvSecurity >= 0 && network.security != WifiAccessPoints.Security.EAP) {
            Intent action = new Intent("com.android.settings.wifi.action.WIFI_CONNECTION_SETTINGS");
            Intent selected = findTrusted(context, action);
            // TCL retains the exported AOSP connector and the same extras, but omits its intent filter.
            if (selected == null) selected = findTrusted(context, new Intent(action).setComponent(
                    new ComponentName("com.android.tv.settings",
                            "com.android.tv.settings.connectivity.WifiConnectionActivity")));
            if (selected != null) return selected.putExtra("wifi_ssid", network.ssid)
                    .putExtra("wifi_security_name", network.security.tvSecurity);
        }
        return SystemSettings.intent(context, Settings.ACTION_WIFI_SETTINGS);
    }

    private static Intent findTrusted(Context context, Intent intent) {
        PackageManager packages = context.getPackageManager();
        // Unlike Intent.resolveActivity(), this verifies the explicit activity really exists.
        for (ResolveInfo candidate : packages.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
            android.content.pm.ActivityInfo activity = candidate.activityInfo;
            int system = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            if (!activity.exported || (activity.applicationInfo.flags & system) == 0) continue;
            if (activity.permission != null && context.checkSelfPermission(activity.permission)
                    != PackageManager.PERMISSION_GRANTED) continue;
            return intent.setComponent(new ComponentName(activity.packageName, activity.name));
        }
        return null;
    }
}
