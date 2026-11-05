package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

/** Standby only: no events, window-content access, key filter, polling, or foreground notification. */
public final class StandbyService extends AccessibilityService {
    private static StandbyService connected;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = this;
    }

    static boolean sleep() {
        if (Build.VERSION.SDK_INT < 28 || connected == null) return false;
        try { return connected.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN); }
        catch (RuntimeException unavailable) { return false; }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        if (connected == this) connected = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        if (connected == this) connected = null;
        super.onDestroy();
    }
}
