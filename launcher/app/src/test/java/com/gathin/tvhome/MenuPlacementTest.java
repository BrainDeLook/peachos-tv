package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class MenuPlacementTest {
    @Test public void firstTileOpensInwardsToTheRight() {
        MenuPlacement p = MenuPlacement.beside(1280, 720, 40, 540, 240, 660, 310, 150, 16, 24, false);
        assertTrue(p.right); assertEquals(256, p.left); assertEquals(525, p.top);
    }
    @Test public void lastTileOpensToTheLeft() {
        MenuPlacement p = MenuPlacement.beside(1280, 720, 1020, 540, 1220, 660, 310, 150, 16, 24, false);
        assertFalse(p.right); assertEquals(694, p.left);
    }
    @Test public void bottomRowStaysInsideTheScreen() {
        MenuPlacement p = MenuPlacement.beside(1280, 720, 400, 650, 600, 710, 310, 180, 16, 24, false);
        assertEquals(516, p.top);
    }
    @Test public void rtlPrefersLeftWhenBothSidesFit() {
        MenuPlacement p = MenuPlacement.beside(1280, 720, 600, 200, 800, 320, 250, 150, 16, 24, true);
        assertFalse(p.right); assertEquals(334, p.left);
    }
    @Test public void narrowScreenClampsWithoutLeavingAnEdge() {
        MenuPlacement p = MenuPlacement.beside(640, 360, 210, 0, 430, 100, 300, 180, 16, 20, false);
        assertTrue(p.left >= 20 && p.left + 300 <= 620); assertEquals(20, p.top);
    }
}
