package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShelfSelectionTest {
    @Test public void cardSourcesNeverUseFullScreenAppArtwork() {
        assertTrue(ShelfSelection.usesBackdrop(ShelfSelection.OFF));
        for (String source : new String[]{ShelfSelection.AUTO, ShelfSelection.CONTINUE,
                ShelfSelection.HISTORY, "channel:42"}) assertFalse(ShelfSelection.usesBackdrop(source));
    }
    @Test public void lampaHistoryRecognizesPublishedIdentity() {
        assertTrue(ShelfSelection.isHistory("top.rootu.lampa", "", "history", "", ""));
        assertTrue(ShelfSelection.isHistory("top.rootu.lampa", "", "", "lampa://top.rootu.lampa/update_channel/history", ""));
        assertFalse(ShelfSelection.isHistory("other.app", "history", "history", "", "История"));
        assertFalse(ShelfSelection.isHistory("top.rootu.lampa", "", "continued", "", "Продолжить просмотр"));
    }
    @Test public void explicitHistoryNeverIncludesRecommendationsOrContinue() {
        assertTrue(ShelfSelection.includes(ShelfSelection.HISTORY, false, -1, true, 42, true));
        assertFalse(ShelfSelection.includes(ShelfSelection.HISTORY, false, -1, true, 43, false));
        assertFalse(ShelfSelection.includes(ShelfSelection.HISTORY, true, 0, true, 42, true));
    }
    @Test public void continueExcludesHiddenAndWatchlistItems() {
        assertTrue(ShelfSelection.includes(ShelfSelection.CONTINUE, true, 0, true, -1, false));
        assertTrue(ShelfSelection.includes(ShelfSelection.CONTINUE, true, 1, true, -1, false));
        assertFalse(ShelfSelection.includes(ShelfSelection.CONTINUE, true, 3, true, -1, false));
        assertFalse(ShelfSelection.includes(ShelfSelection.CONTINUE, true, 0, false, -1, false));
    }
    @Test public void selectedChannelIsExclusive() {
        assertTrue(ShelfSelection.includes("channel:42", false, -1, true, 42, false));
        assertFalse(ShelfSelection.includes("channel:42", false, -1, true, 43, false));
        assertFalse(ShelfSelection.includes(ShelfSelection.OFF, false, -1, true, 42, true));
    }
}
