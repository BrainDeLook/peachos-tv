package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

/** Small permission bridge usable from both the Home panel and its accessibility overlay. */
public final class WifiPermissionActivity extends Activity {
    private static final int REQUEST_SCAN = 41;
    private boolean requesting;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            finish(); return;
        }
        requesting = state != null && state.getBoolean("requesting");
        if (requesting) return; // The framework restores an outstanding permission request.
        SharedPreferences preferences = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        if (preferences.getBoolean("wifi_scan_permission_asked", false)
                && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            new AlertDialog.Builder(this).setTitle(R.string.cc_wifi_allow)
                    .setMessage(R.string.cc_wifi_permission_help)
                    .setPositiveButton(R.string.cc_wifi_open_permissions, (dialog, which) ->
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", getPackageName(), null))))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setOnDismissListener(dialog -> finish()).show();
            return;
        }
        preferences.edit().putBoolean("wifi_scan_permission_asked", true).apply();
        requesting = true;
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_SCAN);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST_SCAN) finish();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("requesting", requesting);
        super.onSaveInstanceState(state);
    }
}
