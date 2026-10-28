package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppSoundStateTest {
    @Test public void coldStartAndRepeatedHomeStaySilent() {
        AppSoundState state = new AppSoundState();
        assertFalse(state.resumed());
        assertFalse(state.resumed());
    }

    @Test public void settingsAndScreenOffDoNotArmReturn() {
        AppSoundState state = new AppSoundState();
        assertFalse(state.paused());
        assertFalse(state.resumed());
    }

    @Test public void actualAppTripPlaysReturnExactlyOnce() {
        AppSoundState state = new AppSoundState();
        state.launched();
        assertTrue(state.paused());
        assertTrue(state.resumed());
        assertFalse(state.resumed());
        assertFalse(state.paused());
        assertFalse(state.resumed());
    }

    @Test public void acceptedLaunchWithoutLeavingHomeDoesNotPlayReturn() {
        AppSoundState state = new AppSoundState();
        state.launched();
        assertFalse(state.resumed());
        assertFalse(state.paused());
        assertFalse(state.resumed());
    }

    @Test public void rapidOpenBackThenAnotherAppDoesNotLoseItsReturn() {
        AppSoundState state = new AppSoundState();
        for (int i = 0; i < 3; i++) {
            state.launched();
            assertTrue(state.paused());
            assertTrue(state.resumed());
        }
    }

    @Test public void duplicatePauseDoesNotReplayOpeningOrDropReturn() {
        AppSoundState state = new AppSoundState();
        state.launched();
        assertTrue(state.paused());
        assertFalse(state.paused());
        assertTrue(state.resumed());
    }
}
