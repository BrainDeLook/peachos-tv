package com.gathin.tvhome;

/** A small window of complete AP rows; navigation never exposes a fraction of the next row. */
final class WifiViewport {
    private int first, capacity;

    boolean fit(int availablePixels, int rowPitchPixels, int total) {
        int nextCapacity = Math.max(0, availablePixels / rowPitchPixels);
        int nextFirst = Math.min(first, Math.max(0, total - nextCapacity));
        boolean changed = capacity != nextCapacity || first != nextFirst;
        capacity = nextCapacity;
        first = nextFirst;
        return changed;
    }

    void reveal(int index, int total) {
        if (index < first) first = index;
        else if (index >= first + capacity) first = index - capacity + 1;
        first = Math.max(0, Math.min(first, Math.max(0, total - capacity)));
    }

    int first() { return first; }
    int end(int total) { return Math.min(total, first + capacity); }
}
