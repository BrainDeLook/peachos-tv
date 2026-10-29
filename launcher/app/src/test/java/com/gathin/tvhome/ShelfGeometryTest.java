package com.gathin.tvhome;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShelfGeometryTest {
    @Test public void focusMovesAcrossFiveVisiblePostersWithoutScrolling() {
        for (int i = 0; i < 5; i++) {
            int left = 80 + i * 360;
            assertEquals(0, ShelfGeometry.revealScroll(0, 1920, 4000, left, left + 320, 80));
        }
    }
    @Test public void nextPosterOnlyRevealsItsHiddenPart() {
        assertEquals(360, ShelfGeometry.revealScroll(0, 1920, 4000, 1880, 2200, 80));
    }
    @Test public void reversingWithinVisiblePostersKeepsTheViewport() {
        assertEquals(360, ShelfGeometry.revealScroll(360, 1920, 4000, 1160, 1480, 80));
        assertEquals(0, ShelfGeometry.revealScroll(360, 1920, 4000, 80, 400, 80));
    }
    @Test public void mixedWidthsUseActualEdgesInsteadOfAnItemIndex() {
        assertEquals(0, ShelfGeometry.revealScroll(0, 1920, 4000, 800, 1124, 80));
        assertEquals(14, ShelfGeometry.revealScroll(0, 1920, 4000, 1170, 1854, 80));
    }
    @Test public void firstAndLastCardsCannotScrollPastTheContent() {
        assertEquals(0, ShelfGeometry.revealScroll(100, 1920, 3500, 40, 360, 80));
        assertEquals(1580, ShelfGeometry.revealScroll(1400, 1920, 3500, 3100, 3480, 80));
        assertEquals(0, ShelfGeometry.revealScroll(0, 1920, 1400, 80, 760, 80));
    }
    @Test public void zeroIsAValidLandscapeHintNotAMissingValue() {
        assertEquals(16f / 9f, ShelfGeometry.ratio(0), .001f);
        assertEquals(2f / 3f, ShelfGeometry.ratio(4), .001f);
        assertEquals(1f, ShelfGeometry.ratio(3), .001f);
    }
    @Test public void wideAndTallImagesRetainTheirOwnAspect() {
        for (float ratio : new float[]{16f/9f, 4f/3f, 1f, 2f/3f}) {
            int[] size = ShelfGeometry.size(ratio, 462, 640);
            assertEquals(ratio, size[0] / (float) size[1], .005f);
            assertTrue(size[0] <= 640); assertTrue(size[1] <= 462);
        }
    }
}
