package com.gathin.tvhome;

/** Android TV aspect-ratio hints; the decoded image's own ratio takes precedence when available. */
final class ShelfGeometry {
    static float ratio(int hint) {
        switch (hint) {
            case 0: return 16f / 9f;
            case 1: return 3f / 2f;
            case 2: return 4f / 3f;
            case 3: return 1f;
            case 5: return 1f / 1.441f;
            case 6: return 3f / 4f;
            default: return 2f / 3f;
        }
    }
    static int[] size(float ratio, int rowHeight, int maxWidth) {
        if (!Float.isFinite(ratio) || ratio <= 0) ratio = 2f / 3f;
        int width = Math.max(1, Math.min(maxWidth, Math.round(rowHeight * ratio)));
        return new int[]{width, Math.max(1, Math.round(width / ratio))};
    }
    private ShelfGeometry() { }
}
