package dev.friendai.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.client.ui.SideNavLayout.Layout;
import dev.friendai.client.ui.SideNavLayout.Rail;
import dev.friendai.client.ui.SideNavLayout.Slot;
import org.junit.jupiter.api.Test;

/**
 * The rail against the windows the menu has to survive: the smallest supported GUI, the common laptop scales and
 * the largest screen. The arithmetic is checked, not a screenshot: every entry inside the rail, the content to its
 * right, and the documented fallback (compact entries, then a last compression, then scrolling) when the height
 * cannot hold them all.
 */
class SideNavLayoutTest {
    private static final int OWNER_TABS = 8;
    private static final int ALL_TABS = 10;
    private static final int OPERATOR_START = 8;

    @Test
    void eightTabsFitTheCommonLaptopWindowAtFullSize() {
        Layout layout = SideNavLayout.arrange(960, 540, OWNER_TABS, -1);
        Rail rail = layout.rail();

        assertEquals(new SideNavLayout.Rect(126, 24, 708, 480), layout.window());
        assertFalse(layout.compact());
        assertEquals(new SideNavLayout.Rect(134, 32, 124, 464), rail.box());
        assertEquals(new SideNavLayout.Rect(266, 32, 560, 464), layout.content());
        assertEquals(0, rail.maxScroll());
        assertEquals(SideNavLayout.ENTRY_HEIGHT, rail.entryHeight());
        assertEquals(new Slot(134, 32, 124, 20), rail.slots().getFirst());
        assertEquals(new Slot(134, 186, 124, 20), rail.slots().getLast());
    }

    @Test
    void theLargestWindowDoesNotStretchTheRail() {
        Layout layout = SideNavLayout.arrange(1920, 1080, OWNER_TABS, -1);

        assertEquals(new SideNavLayout.Rect(606, 24, 708, 1020), layout.window());
        assertEquals(new SideNavLayout.Rect(746, 32, 560, 1004), layout.content());
    }

    @Test
    void tenTabsFitAFullWidthWindowWithoutScrolling() {
        Layout layout = SideNavLayout.arrange(640, 360, ALL_TABS, OPERATOR_START);
        Rail rail = layout.rail();

        assertEquals(new SideNavLayout.Rect(14, 24, 612, 300), layout.window());
        assertEquals(new SideNavLayout.Rect(154, 32, 464, 284), layout.content());
        assertEquals(0, rail.maxScroll());
        assertEquals(SideNavLayout.ENTRY_HEIGHT, rail.entryHeight());
        assertEquals(224, rail.contentHeight());
        assertEquals(210, rail.separatorY());
        assertEquals(new Slot(22, 186, 124, 20), rail.slots().get(7));
        assertEquals(new Slot(22, 214, 124, 20), rail.slots().get(8));
        assertEquals(new Slot(22, 236, 124, 20), rail.slots().get(9));
    }

    @Test
    void theSmallestWindowCompressesTheRailUntilTheOperatorGroupFits() {
        Layout layout = SideNavLayout.arrange(426, 240, ALL_TABS, OPERATOR_START);
        Rail rail = layout.rail();

        assertEquals(new SideNavLayout.Rect(14, 24, 398, 180), layout.window());
        assertTrue(layout.compact());
        assertEquals(new SideNavLayout.Rect(22, 32, 68, 164), rail.box());
        assertEquals(new SideNavLayout.Rect(98, 32, 306, 164), layout.content());
        assertEquals(SideNavLayout.MIN_ENTRY_HEIGHT, rail.entryHeight());
        assertEquals(152, rail.contentHeight());
        assertEquals(0, rail.maxScroll(), "the real ten-entry menu still fits");
        assertEquals(153, rail.separatorY());
        assertEquals(new Slot(22, 32, 68, 14), rail.slots().getFirst());
        assertEquals(new Slot(22, 137, 68, 14), rail.slots().get(7));
        assertEquals(new Slot(22, 155, 68, 14), rail.slots().get(8));
        assertEquals(new Slot(22, 170, 68, 14), rail.slots().get(9));
    }

    @Test
    void eightTabsStayAtTheCompactHeightAtTheSmallestWindow() {
        Layout layout = SideNavLayout.arrange(426, 240, OWNER_TABS, -1);
        Rail rail = layout.rail();

        assertTrue(layout.compact());
        assertEquals(SideNavLayout.COMPACT_ENTRY_HEIGHT, rail.entryHeight());
        assertEquals(135, rail.contentHeight());
        assertEquals(0, rail.maxScroll());
        assertEquals(-1, rail.separatorY());
        assertEquals(151, rail.slots().getLast().y());
        assertTrue(rail.slots().getLast().y() + rail.entryHeight() <= rail.box().bottom());
    }

    @Test
    void theRailGoesCompactExactlyWhenTheContentWouldFallBelowItsMinimum() {
        Layout full = SideNavLayout.arrange(476, 360, OWNER_TABS, -1);
        Layout compact = SideNavLayout.arrange(475, 360, OWNER_TABS, -1);

        assertFalse(full.compact());
        assertEquals(SideNavLayout.MIN_CONTENT_WIDTH, full.content().width());
        assertTrue(compact.compact());
        assertEquals(SideNavLayout.COMPACT_RAIL_WIDTH, compact.rail().box().width());
        assertTrue(compact.content().width() >= SideNavLayout.MIN_CONTENT_WIDTH);
    }

    @Test
    void anEmptyRailIsNotEmptyByAccident() {
        Layout layout = SideNavLayout.arrange(960, 540, 0, -1);

        assertEquals(0, layout.rail().contentHeight());
        assertEquals(0, layout.rail().maxScroll());
        assertEquals(-1, layout.rail().separatorY());
        assertTrue(layout.rail().slots().isEmpty());
    }

    @Test
    void everyWindowKeepsTheRailToTheLeftOfTheContent() {
        int[] widths = {426, 476, 640, 960, 1920};
        int[] heights = {240, 360, 540, 1080};
        for (int width : widths) {
            for (int height : heights) {
                for (int count : new int[] {7, 8, 10}) {
                    int operatorStart = count == 10 ? OPERATOR_START : -1;
                    Layout layout = SideNavLayout.arrange(width, height, count, operatorStart);
                    SideNavLayout.Rect window = layout.window();
                    Rail rail = layout.rail();
                    String where = width + "x" + height + ", " + count + " tabs";

                    assertEquals(SideNavLayout.WINDOW_TOP, window.y(), where);
                    assertTrue(window.x() >= 0, where);
                    assertTrue(window.right() <= width, where);
                    assertTrue(window.bottom() <= height, where);
                    assertEquals(
                            rail.box().right() + SideNavLayout.GAP,
                            layout.content().x(),
                            where);
                    assertEquals(
                            window.y() + SideNavLayout.PAD, layout.content().y(), where);
                    assertTrue(layout.content().right() <= window.right() - SideNavLayout.PAD, where);
                    assertTrue(layout.content().height() > 0, where);
                    assertEquals(count, rail.slots().size(), where);
                    for (Slot slot : rail.slots()) {
                        assertEquals(rail.box().x(), slot.x(), where);
                        assertEquals(rail.box().width(), slot.width(), where);
                        assertEquals(rail.entryHeight(), slot.height(), where);
                        assertTrue(slot.y() >= rail.box().y(), where);
                        assertTrue(slot.y() + slot.height() <= rail.box().y() + rail.contentHeight(), where);
                    }
                    if (rail.maxScroll() == 0) {
                        assertTrue(
                                rail.slots().getLast().y() + rail.entryHeight()
                                        <= rail.box().bottom(),
                                where);
                    }
                    assertTrue(rail.contentHeight() - rail.box().height() <= rail.maxScroll(), where);
                }
            }
        }
    }

    @Test
    void aWindowTooShortForTheContentScrollsInsteadOfHidingEntries() {
        Layout layout = SideNavLayout.arrange(426, 200, ALL_TABS, OPERATOR_START);
        Rail rail = layout.rail();

        assertTrue(rail.maxScroll() > 0);
        assertEquals(rail.contentHeight() - rail.box().height(), rail.maxScroll());
    }
}
