package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsButtonTest {
    @Test public void tapDoesNothing() {
        SettingsButton.Gesture gesture = new SettingsButton.Gesture();
        assertTrue(gesture.down(1000));
        assertFalse(gesture.up(1000, 1120, 500));
        assertFalse(gesture.held());
    }
    @Test public void holdFiresOnceWhileDownAndNeverAgainOnRelease() {
        SettingsButton.Gesture gesture = new SettingsButton.Gesture();
        gesture.down(1000);
        assertTrue(gesture.held());
        assertFalse(gesture.held());
        assertFalse(gesture.up(1000, 2200, 500));
    }
    @Test public void repeatDoesNotRestartOrRearmTheHold() {
        SettingsButton.Gesture gesture = new SettingsButton.Gesture();
        gesture.down(1000);
        assertFalse(gesture.down(1000));
        assertTrue(gesture.held());
        assertFalse(gesture.down(1000));
        assertFalse(gesture.held());
    }
    @Test public void lateTimerStillHonoursTheHoldAtRelease() {
        SettingsButton.Gesture gesture = new SettingsButton.Gesture();
        gesture.down(1000);
        assertTrue(gesture.up(1000, 1500, 500));
        assertFalse(gesture.held());
    }
    @Test public void cancelAndOrphanReleaseCannotOpenAnything() {
        SettingsButton.Gesture gesture = new SettingsButton.Gesture();
        assertFalse(gesture.up(1000, 2000, 500));
        gesture.down(1000); gesture.cancel();
        assertFalse(gesture.held());
        assertFalse(gesture.up(1000, 2000, 500));
    }
    @Test public void nextPressCanOpenAgain() {
        SettingsButton.Gesture gesture = new SettingsButton.Gesture();
        gesture.down(1000); gesture.held(); gesture.up(1000, 1600, 500);
        assertTrue(gesture.down(2000));
        assertTrue(gesture.held());
    }
}
