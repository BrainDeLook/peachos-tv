package com.gathin.tvhome;

import android.graphics.Path;

/** Rounded rectangle with curvature easing into the straight edges, cached by its owner. */
final class ContinuousCorners {
    private ContinuousCorners() { }

    static void set(Path path, float left, float top, float right, float bottom, float radius) {
        path.rewind();
        float limit = Math.min(right - left, bottom - top) * .5f;
        float r = Math.min(radius, limit);
        if (radius >= limit) {
            path.addRoundRect(left, top, right, bottom, r, r, Path.Direction.CW);
            return;
        }
        // Continuous corners need a longer transition than a circular arc for the same apparent rounding.
        r = Math.min(radius * 1.35f, limit);
        path.moveTo(left + r, top);
        path.lineTo(right - r, top);
        path.cubicTo(right - r * .6f, top, right - r * .36f, top, right - r * .18f, top + r * .18f);
        path.cubicTo(right, top + r * .36f, right, top + r * .6f, right, top + r);
        path.lineTo(right, bottom - r);
        path.cubicTo(right, bottom - r * .6f, right, bottom - r * .36f, right - r * .18f, bottom - r * .18f);
        path.cubicTo(right - r * .36f, bottom, right - r * .6f, bottom, right - r, bottom);
        path.lineTo(left + r, bottom);
        path.cubicTo(left + r * .6f, bottom, left + r * .36f, bottom, left + r * .18f, bottom - r * .18f);
        path.cubicTo(left, bottom - r * .36f, left, bottom - r * .6f, left, bottom - r);
        path.lineTo(left, top + r);
        path.cubicTo(left, top + r * .6f, left, top + r * .36f, left + r * .18f, top + r * .18f);
        path.cubicTo(left + r * .36f, top, left + r * .6f, top, left + r, top);
        path.close();
    }
}
