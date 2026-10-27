package dev.friendai.client.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the companion menu puts its window, its navigation rail and the selected tab's content. One window:
 * a rail of tabs down the left, the content to its right, a title above and the Done button below. Pure
 * arithmetic, no drawing.
 *
 * <p>Responsive rules, in order: the window never grows past {@link #MAX_WINDOW_WIDTH} nor past the screen; the
 * rail goes compact when the content would fall below {@link #MIN_CONTENT_WIDTH}; entries shrink once when the
 * height cannot hold them at full size and the rail scrolls when even that is not enough, so an entry is never
 * silently dropped.
 */
final class SideNavLayout {
    static final int MARGIN_X = 14;
    /** The title sits above the window, on the dimmed world. */
    static final int WINDOW_TOP = 24;
    /** Space below the window for the Done button: 8 above it, 20 high, 8 to the screen edge. */
    static final int BOTTOM_RESERVE = 36;
    static final int PAD = 8;

    static final int RAIL_WIDTH = 124;
    static final int COMPACT_RAIL_WIDTH = 68;
    static final int GAP = 8;
    /** Below this the content column stops being usable, so the rail gives up width instead. */
    static final int MIN_CONTENT_WIDTH = 300;
    /** 560 content + 124 rail + 8 gap + 2 x 8 pad: the widest the window ever gets. */
    static final int MAX_WINDOW_WIDTH = 708;

    static final int ENTRY_HEIGHT = 20;
    static final int ENTRY_GAP = 2;
    static final int COMPACT_ENTRY_HEIGHT = 16;
    static final int COMPACT_ENTRY_GAP = 1;
    static final int SEPARATOR = 6;
    static final int COMPACT_SEPARATOR = 4;

    private SideNavLayout() {}

    record Rect(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }
    }

    /** One rail entry: the tab index it stands for, and its rectangle. */
    record Slot(int index, int x, int y, int width, int height) {}

    /**
     * The rail column. {@code slots} are the entries at the top scroll position; a screen showing another
     * position moves them up by that many pixels. {@code separatorY} is -1 when every entry is the owner's.
     */
    record Rail(Rect box, int viewportHeight, int contentHeight, int maxScroll, int entryHeight, int gap,
            List<Slot> slots, int separatorY) {}

    /** The whole layout: the window, its rail and the content rectangle the selected tab draws in. */
    record Layout(Rect window, Rail rail, Rect content, boolean compact) {}

    /**
     * Lays the menu out in a {@code screenWidth} x {@code screenHeight} GUI rectangle.
     *
     * @param entryCount how many tabs are shown (owner tabs, then the operator ones)
     * @param operatorStart the index of the first operator tab, or -1 when there is none
     */
    static Layout arrange(int screenWidth, int screenHeight, int entryCount, int operatorStart) {
        int windowWidth = Math.max(1, Math.min(screenWidth - 2 * MARGIN_X, MAX_WINDOW_WIDTH));
        int windowHeight = Math.max(1, screenHeight - WINDOW_TOP - BOTTOM_RESERVE);
        int windowX = (screenWidth - windowWidth) / 2;
        Rect window = new Rect(windowX, WINDOW_TOP, windowWidth, windowHeight);

        int railWidth = RAIL_WIDTH;
        if (windowWidth - 2 * PAD - railWidth - GAP < MIN_CONTENT_WIDTH) {
            railWidth = COMPACT_RAIL_WIDTH;
        }
        int contentWidth = Math.max(1, windowWidth - 2 * PAD - railWidth - GAP);
        Rect railBox = new Rect(windowX + PAD, WINDOW_TOP + PAD, railWidth, Math.max(1, windowHeight - 2 * PAD));
        Rect content = new Rect(railBox.right() + GAP, WINDOW_TOP + PAD, contentWidth,
                Math.max(1, windowHeight - 2 * PAD));

        boolean grouped = operatorStart >= 0 && operatorStart < entryCount;
        int entryHeight = ENTRY_HEIGHT;
        int gap = ENTRY_GAP;
        int separator = grouped ? SEPARATOR : 0;
        int contentHeight = columnHeight(entryCount, entryHeight, gap, separator);
        if (contentHeight > railBox.height()) {
            entryHeight = COMPACT_ENTRY_HEIGHT;
            gap = COMPACT_ENTRY_GAP;
            separator = grouped ? COMPACT_SEPARATOR : 0;
            contentHeight = columnHeight(entryCount, entryHeight, gap, separator);
        }
        int maxScroll = Math.max(0, contentHeight - railBox.height());

        List<Slot> slots = new ArrayList<>(entryCount);
        int y = railBox.y();
        for (int i = 0; i < entryCount; i++) {
            if (i > 0) {
                y += gap;
                if (grouped && i == operatorStart) {
                    y += separator;
                }
            }
            slots.add(new Slot(i, railBox.x(), y, railWidth, entryHeight));
            y += entryHeight;
        }

        int separatorY = -1;
        if (grouped && operatorStart > 0 && operatorStart < slots.size()) {
            int previousBottom = slots.get(operatorStart - 1).y() + entryHeight;
            int nextTop = slots.get(operatorStart).y();
            separatorY = previousBottom + (nextTop - previousBottom) / 2;
        }

        Rail rail = new Rail(railBox, railBox.height(), contentHeight, maxScroll, entryHeight, gap,
                List.copyOf(slots), separatorY);
        return new Layout(window, rail, content, railWidth == COMPACT_RAIL_WIDTH);
    }

    private static int columnHeight(int entryCount, int entryHeight, int gap, int separator) {
        if (entryCount <= 0) {
            return 0;
        }
        return entryCount * entryHeight + (entryCount - 1) * gap + separator;
    }
}
