package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.regex.Pattern;

/** Installer-owned Home behavior. It never changes the theme or visual preferences. */
final class HomeSetupState {
    private static final String PREFERENCES = "home_setup";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");
    final String stockPackage;
    final boolean filterKeys;

    private HomeSetupState(String stockPackage, boolean filterKeys) {
        this.stockPackage = stockPackage;
        this.filterKeys = filterKeys;
    }

    /** Home-only mode stays outside the key stream until a feature actually needs remote events. */
    boolean keyFilterEnabled(boolean configured, boolean settingsHold, boolean remoteBindings) {
        return configured && (stockPackage.isEmpty() || filterKeys || settingsHold || remoteBindings);
    }

    /** Validate the external installer request once, before persistence or package lookup. */
    static HomeSetupState requested(String stockPackage, boolean filterKeys, String ownerPackage) {
        if (stockPackage == null || stockPackage.length() > 255
                || !stockPackage.isEmpty() && !PACKAGE.matcher(stockPackage).matches())
            throw new IllegalArgumentException("Invalid stock Home package");
        if (stockPackage.equals(ownerPackage))
            throw new IllegalArgumentException("PeachOS cannot redirect itself");
        return new HomeSetupState(stockPackage, filterKeys);
    }

    static HomeSetupState read(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        return new HomeSetupState(preferences.getString("stock_package", ""),
                preferences.getBoolean("filter_keys", false));
    }

    /** Private installation generation survives upgrades, but not uninstall or clearing app data. */
    static String generation(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String current = preferences.getString("install_generation", null);
        if (current != null) return current;
        current = java.util.UUID.randomUUID().toString();
        if (!preferences.edit().putString("install_generation", current).commit())
            throw new IllegalStateException("Could not persist installation generation");
        return current;
    }

    boolean persist(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString("stock_package", stockPackage).putBoolean("filter_keys", filterKeys).commit();
    }
}
