package com.gathin.tvhome;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** A package load must keep the owner order it started with until its generation is committed. */
public class AppReloadPlanTest {
    @Test
    public void reorderDuringLoadingCannotMutateTheOlderRequest() {
        Map<String, Integer> ranks = new HashMap<>();
        ranks.put("lampa/.Home", 0);
        ranks.put("jellyfin/.Home", 1);
        AppAdapter.ReloadPlan loading = new AppAdapter.ReloadPlan(ranks, 2, false);

        ranks.clear();
        ranks.put("jellyfin/.Home", 0);
        ranks.put("lampa/.Home", 1);

        assertEquals(Integer.valueOf(0), loading.ranks.get("lampa/.Home"));
        assertEquals(Integer.valueOf(1), loading.ranks.get("jellyfin/.Home"));
        assertEquals(2, loading.docked);
        assertFalse(loading.reordering);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void aBackgroundLoadCannotRewriteTheCapturedOrder() {
        AppAdapter.ReloadPlan loading = new AppAdapter.ReloadPlan(Map.of("lampa/.Home", 0), 1, false);
        loading.ranks.put("another/.Home", 0);
    }
}
