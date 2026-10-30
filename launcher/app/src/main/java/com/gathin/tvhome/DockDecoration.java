package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** Extra band below the tiles for their names (always_show_names), in px. */
    private final float namesRoom;
    /** Light glass like the top bar's pills (config.xml dock_glass): a white tint that fades downwards. */
    private final boolean lightGlass;
    /** Padding all round the tiles equal to the gap between them (config.xml dock_even_padding). */
    private final boolean evenPadding;
    /** The band's top and bottom the glass gradient was made for; it is remade when they move. */
    private float shaderTop = Float.NaN, shaderBottom = Float.NaN;
    /** single_row: the tiles after the dock share its row, so they take the same offsets to stay level with it. */
    private final boolean singleRow;
    /** dock_band_all: in a single row, the band runs behind every app instead of the dock apps only. */
    private final boolean bandAll;
    private final LiquidGlass liquid;
    private final boolean animateEdges;
    private float leftExpansion, rightExpansion;
    private long edgeFrameNanos;

    /**
     * @param translucent the cinematic shelf's band: the artwork and the glow show through it.
     * @param names       the dock tiles show their names, so the band reaches down over them.
     * @param opacity     the plain band's opacity in percent (dock_opacity), so a wallpaper can show through it.
     * @param lightGlass  a light frosted band like the pills, for a sky or wallpaper behind it.
     * @param evenPadding the band's padding is the gap between the tiles on all four sides (dock_even_padding).
     * @param singleRow   every app sits in the dock's row (single_row); the band covers the dock apps only, unless bandAll.
     * @param bandAll     with singleRow, the band runs behind every app in the row (dock_band_all).
     */
    DockDecoration(int dockSize, float density, boolean translucent, boolean names, int opacity, boolean lightGlass,
                   boolean evenPadding, boolean singleRow, boolean bandAll, boolean liquidGlass, boolean animateEdges) {
        this.dockSize = dockSize;
        this.singleRow = singleRow;
        this.bandAll = singleRow && bandAll;
        this.animateEdges = animateEdges;
        // This band's dimensions stay fixed while scrolling; sideways/variable-padding bands keep direct drawing.
        this.liquid = liquidGlass ? new LiquidGlass(density, 26 * density, !singleRow && !evenPadding) : null;
        this.density = density;
        this.namesRoom = names ? 22 * density : 0;
        this.lightGlass = lightGlass;
        this.evenPadding = evenPadding;
        band.setColor(translucent ? 0x8C121014 : (Math.round(opacity * 2.55f) << 24) | 0x141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(lightGlass ? 0x38FFFFFF : translucent ? 0x26FFFFFF : 0x1FFFFFFF);
    }

    /** Concentric corners: the band's radius is the tiles' radius plus the band's padding around them. */
    static float radiusPx(float tileRadiusPx, float paddingPx) {
        return tileRadiusPx + paddingPx;
    }

    /**
     * The band's padding on every side for dock_even_padding: the gap between two dock tiles (a cell minus its tile, as
     * AppAdapter.tileWidthPx lays them out), capped by the room the grid leaves at its edges.
     */
    static float evenPaddingPx(int innerWidthPx, int columns, int tileWidthPx, float roomPx) {
        float gap = columns > 0 ? innerWidthPx / (float) columns - tileWidthPx : 0f;
        return Math.max(0f, Math.min(gap, roomPx));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && (singleRow || pos < dockSize)) {
            outRect.top = (int) (6 * density);
            // The dock has no app-name row; reclaim part of that reserved space for the next row.
            outRect.bottom = (int) ((namesRoom > 0 ? 12 : -5) * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        float focusedCenter = Float.NaN;
        float artworkLeft = Float.POSITIVE_INFINITY, artworkRight = Float.NEGATIVE_INFINITY;
        int cardWidth = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || (!bandAll && pos >= dockSize)) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            float zoom = Math.max(0f, card.getScaleX() - 1f) * card.getWidth() * .5f;
            artworkLeft = Math.min(artworkLeft, l - zoom);
            artworkRight = Math.max(artworkRight, r + zoom);
            if (card.hasFocus()) focusedCenter = (l + r) * .5f;
            cardWidth = card.getWidth();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) { edgeFrameNanos = 0; return; }
        if (!singleRow) {
            // The configured slot count defines the band, even with a partly empty first row.
            int inner = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            float inset = (inner / (float) dockSize - cardWidth) * .5f;
            rect.left = parent.getPaddingLeft() + inset;
            rect.right = parent.getWidth() - parent.getPaddingRight() - inset;
        }
        float pad = 20.5f * density;
        // Extra paint room at the row ends keeps a zoomed first/last icon inset from the band.
        // The wrapper's wider viewport is balanced by grid padding, so tile positions stay fixed.
        float sidePad = 20 * density;
        float side = Math.max(0f, Math.min(sidePad, Math.min(rect.left, parent.getWidth() - rect.right) - density));
        float radius = AppAdapter.cornerRadius(cardWidth, density);
        if (evenPadding) {
            // The same padding on all four sides, so the band's corners are concentric with the tiles' everywhere.
            int inner = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            // A single row's tiles can run off the screen, so only its top limits the padding; its ends are clamped below.
            float room = (singleRow ? rect.top : Math.min(rect.top, Math.min(rect.left, parent.getWidth() - rect.right))) - density;
            float even = evenPaddingPx(inner, dockSize, cardWidth, room);
            rect.inset(-even, -even);
            radius = radiusPx(radius, even);
        } else {
            // dock_even_padding off: the band as before, with its fixed 26 dp corners.
            rect.inset(singleRow ? -sidePad : -side, -pad);
            radius = 36 * density;
        }
        if (singleRow) {
            // Tiles past the screen's edge: the band stops at the grid's edge with its rounded end instead of being
            // sliced square there.
            rect.left = Math.max(rect.left, density);
            rect.right = Math.min(rect.right, parent.getWidth() - density);
        }
        rect.bottom += namesRoom;
        // The reference keeps 12dp around enlarged end tiles, rather than squeezing them
        // into the idle band's smaller end clearance. Keep the icon spring responsive;
        // only the band's outside clearance eases toward it, with a slower release.
        float drawLeft = rect.left, drawRight = rect.right;
        if (!singleRow && !evenPadding) {
            float targetLeft = Math.max(0f, rect.left - artworkLeft + 12 * density);
            float targetRight = Math.max(0f, artworkRight + 12 * density - rect.right);
            long now = System.nanoTime();
            long elapsed = now - edgeFrameNanos;
            if (!animateEdges || edgeFrameNanos == 0 || elapsed > 200_000_000L) {
                leftExpansion = targetLeft; rightExpansion = targetRight;
            } else {
                float seconds = elapsed * 1e-9f;
                leftExpansion = easeExpansion(leftExpansion, targetLeft, seconds);
                rightExpansion = easeExpansion(rightExpansion, targetRight, seconds);
            }
            edgeFrameNanos = now;
            if (Math.abs(leftExpansion - targetLeft) < .025f * density) leftExpansion = targetLeft;
            if (Math.abs(rightExpansion - targetRight) < .025f * density) rightExpansion = targetRight;
            if (leftExpansion != targetLeft || rightExpansion != targetRight) parent.postInvalidateOnAnimation();
            drawLeft -= leftExpansion;
            drawRight += rightExpansion;
        }
        if (liquid != null) {
            liquid.radius(radius);
            liquid.setBounds(Math.round(rect.left), Math.round(rect.top), Math.round(rect.right), Math.round(rect.bottom));
            liquid.focus(Float.isNaN(focusedCenter) ? .25f : (focusedCenter - rect.left) / rect.width());
            // Stretch the existing band by less than 1%: no bitmap resize/rebuild on each
            // focus frame, and the icon positions and gaps remain untouched.
            int save = c.save();
            c.translate(drawLeft, 0);
            c.scale((drawRight - drawLeft) / rect.width(), 1f);
            c.translate(-rect.left, 0);
            liquid.draw(c);
            c.restoreToCount(save);
            return;
        }
        rect.left = drawLeft; rect.right = drawRight;
        if (lightGlass && (rect.top != shaderTop || rect.bottom != shaderBottom)) {
            // The pills' glass (pill_bg: white, 18% fading to 8%), a little fainter over this much larger area.
            band.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x24FFFFFF, 0x0DFFFFFF,
                    Shader.TileMode.CLAMP));
            shaderTop = rect.top;
            shaderBottom = rect.bottom;
        }
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }

    private static float easeExpansion(float current, float target, float seconds) {
        float response = target > current ? .045f : .085f;
        return target + (current - target) * (float) Math.exp(-seconds / response);
    }
}
