package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class VolumeKeysTest {
    // Android's values, written out: android.view.KeyEvent and AudioManager aren't available in JVM tests.
    private static final int KEYCODE_VOLUME_UP = 24;
    private static final int KEYCODE_VOLUME_DOWN = 25;
    private static final int KEYCODE_VOLUME_MUTE = 164;
    private static final int KEYCODE_DPAD_UP = 19;
    private static final int ADJUST_RAISE = 1;
    private static final int ADJUST_LOWER = -1;
    private static final int ACTION_DOWN = 0;
    private static final int ACTION_UP = 1;

    @Test
    public void volumeUpAndDownAreHandled() {
        assertEquals(ADJUST_RAISE, VolumeKeys.direction(KEYCODE_VOLUME_UP));
        assertEquals(ADJUST_LOWER, VolumeKeys.direction(KEYCODE_VOLUME_DOWN));
    }

    @Test
    public void otherKeysPassThrough() {
        // Mute is one press with nothing to repeat, and the firmware did not double it.
        assertEquals(0, VolumeKeys.direction(KEYCODE_VOLUME_MUTE));
        assertEquals(0, VolumeKeys.direction(KEYCODE_DPAD_UP));
    }

    @Test
    public void repeatedDownsWithZeroRepeatCountDoNotRestartTheDelay() {
        VolumeKeys.Press press = new VolumeKeys.Press();
        assertEquals(VolumeKeys.Press.START, press.update(ACTION_DOWN, KEYCODE_VOLUME_UP, 7, 0, false));
        for (int i = 0; i < 20; i++)
            assertEquals(VolumeKeys.Press.NONE, press.update(ACTION_DOWN, KEYCODE_VOLUME_UP, 7, 0, false));
        assertEquals(VolumeKeys.Press.END, press.update(ACTION_UP, KEYCODE_VOLUME_UP, 7, 0, false));
    }

    @Test
    public void nativeRepeatsDoNotCreateASecondTimer() {
        VolumeKeys.Press press = new VolumeKeys.Press();
        assertEquals(VolumeKeys.Press.START, press.update(ACTION_DOWN, KEYCODE_VOLUME_UP, 7, 0, false));
        assertEquals(VolumeKeys.Press.NONE, press.update(ACTION_DOWN, KEYCODE_VOLUME_UP, 7, 1, false));
        assertEquals(VolumeKeys.Press.NONE, press.update(ACTION_DOWN, KEYCODE_VOLUME_UP, 7, 12, false));
    }

    @Test
    public void oldDirectionReleaseDoesNotCancelTheNewHold() {
        VolumeKeys.Press press = new VolumeKeys.Press();
        press.update(ACTION_DOWN, KEYCODE_VOLUME_UP, 7, 0, false);
        assertEquals(VolumeKeys.Press.START, press.update(ACTION_DOWN, KEYCODE_VOLUME_DOWN, 7, 0, false));
        assertEquals(VolumeKeys.Press.NONE, press.update(ACTION_UP, KEYCODE_VOLUME_UP, 7, 0, false));
        assertEquals(VolumeKeys.Press.END, press.update(ACTION_UP, KEYCODE_VOLUME_DOWN, 7, 0, false));
    }

    @Test
    public void releaseFromAnotherRemoteDoesNotCancelTheHold() {
        VolumeKeys.Press press = new VolumeKeys.Press();
        press.update(ACTION_DOWN, KEYCODE_VOLUME_UP, 7, 0, false);
        assertEquals(VolumeKeys.Press.NONE, press.update(ACTION_UP, KEYCODE_VOLUME_UP, 8, 0, false));
        assertEquals(VolumeKeys.Press.END, press.update(ACTION_UP, KEYCODE_VOLUME_UP, 7, 0, false));
    }

    @Test
    public void canceledPressAndServiceCleanupAllowANewHold() {
        VolumeKeys.Press press = new VolumeKeys.Press();
        press.update(ACTION_DOWN, KEYCODE_VOLUME_UP, 7, 0, false);
        assertEquals(VolumeKeys.Press.END, press.update(ACTION_UP, KEYCODE_VOLUME_UP, 7, 0, true));
        assertEquals(VolumeKeys.Press.START, press.update(ACTION_DOWN, KEYCODE_VOLUME_UP, 7, 0, false));
        press.clear();
        assertEquals(VolumeKeys.Press.START, press.update(ACTION_DOWN, KEYCODE_VOLUME_UP, 7, 0, false));
    }

    @Test
    public void releasingATapAllowsTheNextTapWithoutAddingASoftwareFirstStep() {
        VolumeKeys.Press press = new VolumeKeys.Press();
        assertEquals(VolumeKeys.Press.START, press.update(ACTION_DOWN, KEYCODE_VOLUME_UP, 7, 0, false));
        assertEquals(VolumeKeys.Press.END, press.update(ACTION_UP, KEYCODE_VOLUME_UP, 7, 0, false));
        assertEquals(VolumeKeys.Press.NONE, press.update(ACTION_UP, KEYCODE_VOLUME_UP, 7, 0, false));
        assertEquals(VolumeKeys.Press.START, press.update(ACTION_DOWN, KEYCODE_VOLUME_UP, 7, 0, false));
    }

    @Test
    public void aRepeatWithoutAnInitialDownDoesNotStartSyntheticRepeating() {
        VolumeKeys.Press press = new VolumeKeys.Press();
        assertEquals(VolumeKeys.Press.NONE, press.update(ACTION_DOWN, KEYCODE_VOLUME_UP, 7, 1, false));
        assertEquals(VolumeKeys.Press.NONE, press.update(ACTION_UP, KEYCODE_VOLUME_UP, 7, 0, false));
    }
}
