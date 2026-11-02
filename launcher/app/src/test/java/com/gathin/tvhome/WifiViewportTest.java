package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class WifiViewportTest {
    @Test public void onlyWholeRowsFitAtEveryDensity() {
        for (int pitch : new int[]{51, 77, 102, 153}) {
            for (int available = 0; available < 800; available++) {
                WifiViewport viewport = new WifiViewport();
                viewport.fit(available, pitch, 32);
                int rows = viewport.end(32) - viewport.first();
                assertTrue(rows * pitch <= available);
                assertTrue((rows + 1) * pitch > available || rows == 32);
            }
        }
    }
    @Test public void focusStaysInsideWindowAtBothEnds() {
        WifiViewport viewport = new WifiViewport();
        viewport.fit(3 * 102, 102, 32);
        for (int index = 0; index < 32; index++) {
            viewport.reveal(index, 32);
            assertTrue(viewport.first() <= index && index < viewport.end(32));
            assertEquals(3, viewport.end(32) - viewport.first());
        }
        for (int index = 31; index >= 0; index--) {
            viewport.reveal(index, 32);
            assertTrue(viewport.first() <= index && index < viewport.end(32));
        }
        assertEquals(0, viewport.first());
    }
    @Test public void shrinkingDataAndResizingClampsTheWindow() {
        WifiViewport viewport = new WifiViewport();
        viewport.fit(306, 102, 32);
        viewport.reveal(31, 32);
        assertEquals(29, viewport.first());
        viewport.fit(306, 102, 2);
        assertEquals(0, viewport.first());
        assertEquals(2, viewport.end(2));
        viewport.fit(510, 102, 32);
        viewport.reveal(31, 32);
        assertEquals(27, viewport.first());
        viewport.fit(0, 102, 0);
        assertEquals(0, viewport.end(0));
    }
    @Test public void noLayoutWorkForUnchangedCapacity() {
        WifiViewport viewport = new WifiViewport();
        assertTrue(viewport.fit(310, 102, 32));
        assertFalse(viewport.fit(311, 102, 32));
        assertTrue(viewport.fit(408, 102, 32));
    }
}
