package dev.friendai.client.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the companion menu's tabs go. One row squeezes the labels once there are more than a handful of tabs, so
 * they wrap into as many rows as the width needs, each row centred under the title. Pure arithmetic, no drawing.
 */
final class TabBarLayout {
    static final int TAB_HEIGHT = 20;
    static final int TAB_GAP = 2;
    /** Below this a tab label stops being readable, so the bar takes another row instead. */
    static final int MIN_TAB_WIDTH = 76;
    static final int MAX_TAB_WIDTH = 120;

    private TabBarLayout() {}

    /** One tab: the row it sits in and its top-left corner. */
    record Slot(int row, int x, int y) {}

    /** The whole bar: the row count, the width of a tab, its height and one slot per tab, in order. */
    record Bar(int rows, int perRow, int tabWidth, int height, List<Slot> slots) {}

    /**
     * Lays {@code tabCount} tabs out in the rectangle starting at ({@code left}, {@code top}) with
     * {@code contentWidth} pixels. Rows share the tabs evenly, so nine tabs make rows of five and four rather than
     * seven and two, and a shorter last row is centred.
     */
    static Bar arrange(int tabCount, int contentWidth, int left, int top) {
        if (tabCount <= 0) {
            return new Bar(0, 0, 0, 0, List.of());
        }
        int perRowLimit = Math.max(1, contentWidth / MIN_TAB_WIDTH);
        int rows = (tabCount + perRowLimit - 1) / perRowLimit;
        int perRow = (tabCount + rows - 1) / rows;
        int tabWidth = Math.max(1, Math.min(contentWidth / perRow, MAX_TAB_WIDTH));
        List<Slot> slots = new ArrayList<>(tabCount);
        for (int i = 0; i < tabCount; i++) {
            int row = i / perRow;
            int column = i - row * perRow;
            int inRow = Math.min(perRow, tabCount - row * perRow);
            int rowLeft = left + (contentWidth - inRow * tabWidth) / 2;
            slots.add(new Slot(row, rowLeft + column * tabWidth, top + row * (TAB_HEIGHT + TAB_GAP)));
        }
        int height = rows * TAB_HEIGHT + (rows - 1) * TAB_GAP;
        return new Bar(rows, perRow, tabWidth, height, List.copyOf(slots));
    }
}
