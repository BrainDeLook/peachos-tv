package com.gathin.tvhome;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShelfGeometryTest {
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
