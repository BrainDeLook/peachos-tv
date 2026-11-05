package com.gathin.tvhome;

import android.view.KeyEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteEventCoordinatorTest {
    private RemoteEventCoordinator.Event key(int code, int action, long down, long time) {
        return new RemoteEventCoordinator.Event(2, 257, code, 42, action, 0, down, time);
    }
    @Test public void twoServicesExecuteOneDownAndOneUpOnly() {
        RemoteEventCoordinator.Router router = new RemoteEventCoordinator.Router(); int[] calls = new int[2];
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            RemoteEventCoordinator.Event event = key(KeyEvent.KEYCODE_SETTINGS, action, 100, 100 + action * 600);
            for (int copy=0; copy<2; copy++) assertTrue(router.dispatch(event, slot -> true, slot -> { calls[slot]++; return true; }));
        }
        assertEquals(2, calls[0]); assertEquals(0, calls[1]);
    }
    @Test public void inputOnlyBindingFallsThroughHomeOnce() {
        RemoteEventCoordinator.Router router = new RemoteEventCoordinator.Router(); int[] calls = new int[2];
        assertTrue(router.dispatch(key(178, 0, 10, 10), slot -> true, slot -> {calls[slot]++; return slot==1;}));
        assertTrue(router.dispatch(key(178, 1, 10, 20), slot -> true, slot -> {calls[slot]++; return slot==1;}));
        assertEquals(1, calls[0]); assertEquals(2, calls[1]);
    }
    @Test public void reboundServiceDoesNotInheritHeldKeyButTheUpIsStillConsumed() {
        RemoteEventCoordinator.Router router = new RemoteEventCoordinator.Router(); int[] calls = {0};
        assertTrue(router.dispatch(key(83, 0, 10, 10), slot -> true, slot -> { calls[0]++; return true; }));
        router.changed(0);
        assertTrue(router.dispatch(key(83, 1, 10, 50), slot -> true, slot -> { calls[0]++; return true; }));
        assertEquals(1, calls[0]);
    }
    @Test public void orphanUpNeverLaunchesAnAction() {
        RemoteEventCoordinator.Router router = new RemoteEventCoordinator.Router();
        assertFalse(router.dispatch(key(83, 1, 10, 50), slot -> true, slot -> {fail("orphan action"); return true;}));
    }
    @Test public void compatibleHomeObservesBothNativeEventsWithoutConsuming() {
        RemoteEventCoordinator.Router router = new RemoteEventCoordinator.Router(); int[] calls = {0};
        assertFalse(router.dispatch(key(KeyEvent.KEYCODE_HOME, 0, 10, 10), slot -> slot==0, slot -> {calls[0]++; return false;}));
        assertFalse(router.dispatch(key(KeyEvent.KEYCODE_HOME, 1, 10, 20), slot -> slot==0, slot -> {calls[0]++; return false;}));
        assertEquals(2, calls[0]);
    }
    @Test public void newHomeObserverCannotActOnItsPredecessorsRelease() {
        RemoteEventCoordinator.Router router = new RemoteEventCoordinator.Router(); int[] calls = {0};
        router.dispatch(key(KeyEvent.KEYCODE_HOME, 0, 10, 10), slot -> slot==0, slot -> {calls[0]++; return false;});
        router.changed(0);
        router.dispatch(key(KeyEvent.KEYCODE_HOME, 1, 10, 20), slot -> slot==0, slot -> {calls[0]++; return false;});
        assertEquals(1, calls[0]);
    }
    @Test public void separatePhysicalPressesAreNotDeduplicated() {
        RemoteEventCoordinator.Router router = new RemoteEventCoordinator.Router(); int[] calls = {0};
        for(long down : new long[]{10, 100}) for(int action : new int[]{0, 1})
            router.dispatch(key(83, action, down, down+action*20), slot -> true, slot -> { calls[0]++; return true; });
        assertEquals(4, calls[0]);
    }
    @Test public void cancelledLearningStopsWaitingEvenWhenNoUpArrives() {
        SettingsKeyBinding.Learning learning = new SettingsKeyBinding.Learning((code, scan) -> fail("cancelled learning"));
        learning.downTime = 100; learning.cancel(500);
        assertFalse(learning.expired(2499)); assertTrue(learning.expired(2500)); assertNull(learning.callback);
    }
    @Test public void vendorCodeCollisionsUseScanWhileStandardSettingsIsSemantic() {
        assertTrue(SettingsKeyBinding.matches(196, 485, 196, 485));
        assertFalse(SettingsKeyBinding.matches(196, 485, 196, 490));
        assertTrue(SettingsKeyBinding.matches(KeyEvent.KEYCODE_SETTINGS, 240, KeyEvent.KEYCODE_SETTINGS, 561));
    }
    @Test public void softwareVolumeRepeatHasAFiniteLifetime() {
        assertFalse(VolumeKeys.expired(1000, 1000 + VolumeKeys.MAX_HOLD_MS-1));
        assertTrue(VolumeKeys.expired(1000, 1000 + VolumeKeys.MAX_HOLD_MS));
    }
}
