package com.gathin.tvhome;

/** Keep the highest clearance reached during key repeat, including the scroll's release tail. */
final class ShelfHeadingHold {
    private boolean repeating, holding;
    private int highest = Integer.MAX_VALUE;

    void repeat() { repeating = true; holding = true; }
    void release(boolean scrolling) {
        repeating = false;
        if (!scrolling) reset();
    }
    void settled() { if (!repeating) reset(); }
    int top(int artworkTop) {
        if (!holding) return artworkTop;
        highest = Math.min(highest, artworkTop);
        return highest;
    }
    void reset() {
        repeating = false; holding = false; highest = Integer.MAX_VALUE;
    }
}
