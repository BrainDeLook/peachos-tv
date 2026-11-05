package com.gathin.tvhome;

/** Reuses an already connected helper; opening Sleep never enables a second service. */
final class SystemSleep {
    private SystemSleep() { }

    static boolean sleep() {
        return HomeKeyService.sleep() || StandbyService.sleep();
    }
}
