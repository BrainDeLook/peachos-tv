package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;
import android.widget.Toast;

/** A short, skippable guide for people who install the APK without the PC wizard. */
public final class FirstRunActivity extends Activity {
    static final String DONE_KEY = "first_run_done";
    private TextView home;
    private TextView channels;
    private TextView remote;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_first_run);
        home = findViewById(R.id.firstRunHome);
        channels = findViewById(R.id.firstRunChannels);
        remote = findViewById(R.id.firstRunRemote);
        home.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager roles = getSystemService(RoleManager.class);
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)
                        && !roles.isRoleHeld(RoleManager.ROLE_HOME)
                        && SystemSettings.start(this, roles.createRequestRoleIntent(RoleManager.ROLE_HOME))) return;
            }
            if (!SystemSettings.start(this, new Intent(Settings.ACTION_HOME_SETTINGS)))
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        });
        channels.setOnClickListener(v -> {
            TvMetadata.Status access = TvMetadata.access(this);
            if (access == TvMetadata.Status.PERMISSION_REQUIRED) {
                requestPermissions(new String[]{Backdrops.PERMISSION}, 1);
            } else if (access == TvMetadata.Status.READY) {
                startActivity(new Intent(this, TopShelfSettingsActivity.class));
            } else {
                Toast.makeText(this, TvMetadata.message(access), Toast.LENGTH_LONG).show();
            }
        });
        remote.setOnClickListener(v -> {
            if (!SystemSettings.start(this, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        });
        findViewById(R.id.firstRunDone).setOnClickListener(v -> {
            getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit().putBoolean(DONE_KEY, true).apply();
            finish();
        });
        home.setOnFocusChangeListener((v, focused) -> { if (focused) hint(R.string.first_run_home_hint); });
        channels.setOnFocusChangeListener((v, focused) -> { if (focused) hint(R.string.first_run_channels_hint); });
        remote.setOnFocusChangeListener((v, focused) -> { if (focused) hint(R.string.first_run_remote_hint); });
        findViewById(R.id.firstRunDone).setOnFocusChangeListener((v, focused) -> {
            if (focused) hint(R.string.first_run_hint);
        });
        Config config = Config.load(this);
        SettingsFocusMotion.bind(findViewById(android.R.id.content),
                () -> config.animations && !config.reducedMotion);
        home.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        boolean homeReady = false;
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager roles = getSystemService(RoleManager.class);
            homeReady = roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)
                    && roles.isRoleHeld(RoleManager.ROLE_HOME);
        }
        if (!homeReady) {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolved = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            homeReady = resolved != null && resolved.activityInfo != null
                    && getPackageName().equals(resolved.activityInfo.packageName);
        }
        home.setText(getString(R.string.first_run_step_state, getString(R.string.first_run_home),
                getString(homeReady ? R.string.first_run_ready : R.string.first_run_check)));
        TvMetadata.Status access = TvMetadata.access(this);
        channels.setText(getString(R.string.first_run_step_state, getString(R.string.first_run_channels),
                getString(access == TvMetadata.Status.READY ? R.string.first_run_ready
                        : access == TvMetadata.Status.PROVIDER_MISSING ? R.string.first_run_unavailable
                        : R.string.first_run_check)));
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        boolean serviceReady = false;
        if (manager != null) {
            for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                android.content.pm.ServiceInfo service = info.getResolveInfo().serviceInfo;
                if (getPackageName().equals(service.packageName) && HomeKeyService.class.getName().equals(service.name)) {
                    serviceReady = true;
                    break;
                }
            }
        }
        remote.setText(getString(R.string.first_run_step_state, getString(R.string.first_run_remote),
                getString(serviceReady ? R.string.first_run_ready : R.string.first_run_check)));
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            ChannelSetup.refresh(this);
            startActivity(new Intent(this, TopShelfSettingsActivity.class));
        }
    }

    private void hint(int text) {
        ((TextView) findViewById(R.id.firstRunHint)).setText(text);
    }
}
