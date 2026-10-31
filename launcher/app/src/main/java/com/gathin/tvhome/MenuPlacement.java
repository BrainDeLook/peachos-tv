package com.gathin.tvhome;

/** Placement in the host's local coordinates, including the focused tile's zoom. */
final class MenuPlacement {
    final int left, top;
    final boolean right;

    private MenuPlacement(int left, int top, boolean right) {
        this.left = left; this.top = top; this.right = right;
    }

    static MenuPlacement beside(int width, int height, int left, int top, int right, int bottom,
                                int menuWidth, int menuHeight, int gap, int margin, boolean rtl) {
        int availableRight = width - margin - right - gap;
        int availableLeft = left - gap - margin;
        boolean onRight = availableRight >= menuWidth && (!rtl || availableLeft < menuWidth)
                || availableLeft < menuWidth && availableRight >= availableLeft;
        int x = onRight ? right + gap : left - gap - menuWidth;
        int y = (top + bottom - menuHeight) / 2;
        return new MenuPlacement(Math.max(margin, Math.min(width - margin - menuWidth, x)),
                Math.max(margin, Math.min(height - margin - menuHeight, y)), onRight);
    }
}
