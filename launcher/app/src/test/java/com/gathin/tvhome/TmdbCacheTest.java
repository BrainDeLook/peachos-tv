package com.gathin.tvhome;

import org.junit.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class TmdbCacheTest {
    private static final long NOW = 100 * TmdbCache.DAY_MS;

    @Test public void preservesFreshPositiveAndNegativeResults() {
        TmdbCache.Entry found = TmdbCache.parse((NOW - 29 * TmdbCache.DAY_MS) + "|/one.jpg,/two.jpg|/logo.png", NOW);
        assertNotNull(found);
        assertEquals("/one.jpg,/two.jpg", found.paths);
        assertNotNull(TmdbCache.parse((NOW - 2 * TmdbCache.DAY_MS) + "||", NOW));
    }

    @Test public void positiveAndNegativeResultsExpireAtTheirOwnBoundary() {
        assertNull(TmdbCache.parse((NOW - 30 * TmdbCache.DAY_MS) + "|/one.jpg|", NOW));
        assertNull(TmdbCache.parse((NOW - 3 * TmdbCache.DAY_MS) + "||", NOW));
    }

    @Test public void malformedTimestampAndOldFormatAreCacheMisses() {
        for (String raw : new String[]{"broken|/one.jpg|", "9999999999999999999999||", "time|old", "", "-1||"})
            assertNull(raw, TmdbCache.parse(raw, NOW));
    }

    @Test public void clockRollbackDoesNotMakeAnEntryImmortal() {
        assertNull(TmdbCache.parse((NOW + 1) + "|/one.jpg|", NOW));
    }

    @Test public void invalidPathsCannotBreakRandomArtworkSelection() {
        for (String paths : new String[]{",", "/one.jpg,", "/", "bad.jpg"})
            assertNull(TmdbCache.parse(NOW + "|" + paths + "|", NOW));
        assertNull(TmdbCache.parse(NOW + "|/one.jpg|bad.png", NOW));
    }

    @Test public void cleanupDropsWrongTypesExpiredAndBrokenValues() {
        Map<String, Object> stored = new HashMap<>();
        stored.put("wrong type", 7L);
        stored.put("broken", "bad||");
        stored.put("expired", (NOW - 30 * TmdbCache.DAY_MS) + "|/one.jpg|");
        stored.put("fresh", NOW + "|/one.jpg|");
        List<String> remove = TmdbCache.evictions(stored, NOW);
        assertEquals(3, remove.size());
        assertFalse(remove.contains("fresh"));
    }

    @Test public void countLimitEvictsOldestAndPreservesNewest() {
        Map<String, Object> stored = new HashMap<>();
        for (int i = 0; i < TmdbCache.MAX_ENTRIES + 8; i++) stored.put("title" + i, (NOW - 1000 + i) + "|/one.jpg|");
        List<String> remove = TmdbCache.evictions(stored, NOW);
        assertEquals(8, remove.size());
        for (int i = 0; i < 8; i++) assertTrue(remove.contains("title" + i));
        assertFalse(remove.contains("title519"));
    }
}
