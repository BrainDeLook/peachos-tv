package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArtworkSamplingTest {
    @Test public void aFourKStillNeedsOnlyOneQuarterOfItsPixelsOnFullHd() {
        assertEquals(2, ArtworkSampling.forTarget(3840, 2160, 1920, 1080));
    }
    @Test public void wallCardsAreDecodedForTheirOwnCellRatherThanTheWholeScreen() {
        assertEquals(4, ArtworkSampling.forTarget(3840, 2160, 957, 537));
    }
    @Test public void cropRetainsEnoughPixelsInBothDimensions() {
        assertEquals(1, ArtworkSampling.forTarget(3840, 1440, 1920, 1080));
        assertEquals(2, ArtworkSampling.forTarget(2160, 3840, 960, 540));
    }
    @Test public void smallerOrUnreadableArtworkIsNotSubsampled() {
        assertEquals(1, ArtworkSampling.forTarget(853, 480, 960, 540));
        assertEquals(1, ArtworkSampling.forTarget(-1, -1, 960, 540));
    }
}
