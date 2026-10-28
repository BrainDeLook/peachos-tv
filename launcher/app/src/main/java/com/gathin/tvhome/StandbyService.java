package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
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
        return Build.VERSION.SDK_INT >= 28 && connected != null
                && connected.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        connected = null;
        super.onDestroy();
    }
}
