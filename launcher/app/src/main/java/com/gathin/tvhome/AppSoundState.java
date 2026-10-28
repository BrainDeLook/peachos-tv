package com.gathin.tvhome;

/** Only a successful app launch followed by a pause can arm one return sound.
 * Process-local and never persisted: a cold Home start must stay silent. */
final class AppSoundState {
    private boolean leavingApp;
    private boolean returningFromApp;

    void launched() {
        leavingApp = true;
    }

    /** True only for the pause immediately following the launch: let its opening tail finish. */
    boolean paused() {
        boolean opening = leavingApp;
        if (opening) returningFromApp = true;
        leavingApp = false;
        return opening;
    }

    boolean resumed() {
        boolean playReturn = returningFromApp;
        returningFromApp = false;
        leavingApp = false;
        return playReturn;
    }
}
