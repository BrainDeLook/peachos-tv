package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;

/** The standard settings intent, or, when nothing usable answers it, the first vendor settings entry
 *  from settings_fallback_components that is installed (Xiaomi China, TCL / FFALCON, Fire OS). */
final class SystemSettings {
    private SystemSettings() {}

    static Intent intent(Context context, String action) {
        PackageManager pm = context.getPackageManager();
        Intent standard = new Intent(action);
        ResolveInfo resolved = pm.resolveActivity(standard, PackageManager.MATCH_DEFAULT_ONLY);
        // Fire OS resolves the standard actions to a CTS compliance stub that opens nothing;
        // treat it as missing so the vendor fallback runs.
        if (usable(context, resolved)) return standard;
        if (Settings.ACTION_BLUETOOTH_SETTINGS.equals(action)) {
            Intent accessories = new Intent("com.nes.intent.action.ACCESSORIES");
            if (usable(context, pm.resolveActivity(accessories, PackageManager.MATCH_DEFAULT_ONLY))) return accessories;
            return intent(context, Settings.ACTION_SETTINGS);
        }
        Intent vendor = vendorIntent(context);
        return vendor != null ? vendor : standard;
    }

    /** The first vendor settings entry from settings_fallback_components that is installed, or null
     *  when none is. China-market firmware keeps pages the AOSP TV settings has no row for -
     *  Xiaomi's Account & Security, where the ADB switch lives - in the vendor's own settings app;
     *  MainActivity's Settings menu offers it as "Native settings" while one is installed. */
    static Intent vendorIntent(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String value : context.getResources().getStringArray(R.array.settings_fallback_components)) {
            ComponentName component = ComponentName.unflattenFromString(value);
            if (component == null) continue;
            Intent candidate = new Intent().setComponent(component);
            // PackageManager verifies the actual class. Intent.resolveActivity would return an
            // explicit component even when that class was removed by an OEM update.
            if (usable(context, pm.resolveActivity(candidate, 0))) return candidate;
        }
        return null;
    }

    /** The TV's App info page for pkg, or null where the firmware has none (CVTE's settings app, #1202). */
    static Intent appInfo(Context context, String pkg) {
        Intent info = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        return usable(context, context.getPackageManager().resolveActivity(info, PackageManager.MATCH_DEFAULT_ONLY)) ? info : null;
    }

    private static boolean usable(Context context, ResolveInfo result) {
        if (result == null) return false;
        ActivityInfo activity = result.activityInfo;
        return activity.enabled && activity.applicationInfo.enabled && !activity.name.contains("CTSDummy")
                && (activity.exported || context.getPackageName().equals(activity.packageName))
                && (activity.permission == null || context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED);
    }

    /** Invocation is the final boundary: a resolved component may be disabled or denied meanwhile. */
    static boolean start(Context context, Intent intent) {
        if (launch(context, intent)) return true;
        String action = intent.getAction();
        boolean settings = action != null && (action.startsWith("android.settings.")
                || action.equals("com.nes.intent.action.ACCESSORIES"));
        for (String value : context.getResources().getStringArray(R.array.settings_fallback_components)) {
            ComponentName component = ComponentName.unflattenFromString(value);
            if (component != null && component.equals(intent.getComponent())) settings = true;
        }
        if (!settings) return false;
        PackageManager pm = context.getPackageManager();
        Intent general = new Intent(Settings.ACTION_SETTINGS);
        if (!Settings.ACTION_SETTINGS.equals(action) && usable(context, pm.resolveActivity(general, 0))
                && launch(context, general)) return true;
        for (String value : context.getResources().getStringArray(R.array.settings_fallback_components)) {
            ComponentName component = ComponentName.unflattenFromString(value);
            if (component == null || component.equals(intent.getComponent())) continue;
            Intent candidate = new Intent().setComponent(component);
            if (usable(context, pm.resolveActivity(candidate, 0)) && launch(context, candidate)) return true;
        }
        return false;
    }

    private static boolean launch(Context context, Intent intent) {
        try {
            context.startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }
}
