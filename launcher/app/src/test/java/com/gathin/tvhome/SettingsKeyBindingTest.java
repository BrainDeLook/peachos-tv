package com.gathin.tvhome;

import android.view.KeyEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsKeyBindingTest {
    @Test public void learnedKeycodeDoesNotDependOnScanCode() {
        assertTrue(SettingsKeyBinding.matches(83, 240, 83, 561));
        assertFalse(SettingsKeyBinding.matches(83, 240, 176, 240));
    }
    @Test public void unknownKeyUsesNonzeroScanCode() {
        assertTrue(SettingsKeyBinding.matches(0, 561, 0, 561));
        assertFalse(SettingsKeyBinding.matches(0, 561, 0, 240));
        assertFalse(SettingsKeyBinding.matches(0, 561, KeyEvent.KEYCODE_VOLUME_UP, 561));
        assertFalse(SettingsKeyBinding.matches(0, 0, 0, 0));
        assertFalse(SettingsKeyBinding.matches(-1, 561, 0, 561));
    }
    @Test public void essentialRemoteControlsCannotBeReassigned() {
        int[] protectedKeys = {KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_POWER,
                KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_ALL_APPS,
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER};
        for (int code : protectedKeys) assertFalse("key " + code, SettingsKeyBinding.canLearn(code, 240));
    }
    @Test public void standardAndVendorSettingsCanBeLearned() {
        assertTrue(SettingsKeyBinding.canLearn(176, 0));
        assertTrue(SettingsKeyBinding.canLearn(83, 240));
        assertTrue(SettingsKeyBinding.canLearn(4077, 561));
        assertTrue(SettingsKeyBinding.canLearn(0, 561));
        assertFalse(SettingsKeyBinding.canLearn(0, 0));
    }
}
