package dev.friendai.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Vertical scrolling over content of a known height, with a thin scrollbar. Call {@link #begin} before drawing the
 * rows from {@link #top()} and {@link #end} after.
 */
final class ScrollView {
    private static final int STEP = 18;

    private final boolean follow;
    private int offset;
    private boolean pinned = true;
    private int x;
    private int y;
    private int w;
    private int h;
    private int contentHeight;

    /** @param follow keep the end in view while the user has not scrolled away from it (a log). */
    ScrollView(boolean follow) {
        this.follow = follow;
    }

    void begin(GuiGraphicsExtractor g, int x, int y, int w, int h, int contentHeight) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.contentHeight = contentHeight;
        int max = Math.max(0, contentHeight - h);
        if (follow && pinned) {
            offset = max;
        }
        offset = Math.max(0, Math.min(offset, max));
        pinned = offset >= max;
        g.enableScissor(x, y, x + w, y + h);
    }

    /** The y of the first row's top edge. */
    int top() {
        return y - offset;
    }

    boolean visible(int rowTop, int rowHeight) {
        return rowTop + rowHeight > y && rowTop < y + h;
    }

    /** A screen y inside the view as a content y, for hit testing. */
    int contentY(double screenY) {
        return (int) (screenY - y + offset);
    }

    void end(GuiGraphicsExtractor g) {
        g.disableScissor();
        Ui.scrollbar(g, x + w - 3, y, h, contentHeight, offset, h);
    }

    boolean contains(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Moves the view by {@code pixels} (positive is down); the next {@link #begin} clamps and re-pins it. */
    void nudge(int pixels) {
        offset = Math.max(0, offset + pixels);
        pinned = false;
    }

    boolean scroll(double amount) {
        offset = Math.max(0, offset - (int) Math.round(amount * STEP));
        pinned = false;
        return true;
    }
}
