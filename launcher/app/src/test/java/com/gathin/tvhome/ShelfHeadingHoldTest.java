package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ShelfHeadingHoldTest {
    @Test public void ordinaryStepsFollowArtworkWithoutWaiting() {
        ShelfHeadingHold hold = new ShelfHeadingHold();
        assertEquals(0, hold.top(0));
        assertEquals(90, hold.top(90));
    }
    @Test public void repeatKeepsHighestPositionAcrossLowGapsAndReleaseTail() {
        ShelfHeadingHold hold = new ShelfHeadingHold();
        hold.repeat();
        assertEquals(90, hold.top(90));
        assertEquals(0, hold.top(0));
        assertEquals(0, hold.top(90));
        hold.release(true);
        assertEquals(0, hold.top(90));
        hold.settled();
        assertEquals(90, hold.top(90));
    }
    @Test public void settledViewportDoesNotReleaseWhileKeyIsStillDown() {
        ShelfHeadingHold hold = new ShelfHeadingHold();
        hold.repeat(); hold.top(0); hold.settled();
        assertEquals(0, hold.top(90));
        hold.release(false);
        assertEquals(90, hold.top(90));
    }
    @Test public void leavingRowClearsOldHighWaterMark() {
        ShelfHeadingHold hold = new ShelfHeadingHold();
        hold.repeat(); hold.top(0); hold.reset(); hold.repeat();
        assertEquals(90, hold.top(90));
    }
}
