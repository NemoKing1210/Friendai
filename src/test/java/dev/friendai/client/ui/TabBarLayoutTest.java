package dev.friendai.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.client.ui.TabBarLayout.Bar;
import dev.friendai.client.ui.TabBarLayout.Slot;
import java.util.List;
import org.junit.jupiter.api.Test;

class TabBarLayoutTest {
    @Test
    void nineTabsWrapIntoTwoEvenRows() {
        Bar bar = TabBarLayout.arrange(9, 560, 100, 24);

        assertEquals(2, bar.rows());
        assertEquals(5, bar.perRow());
        assertEquals(112, bar.tabWidth());
        assertEquals(42, bar.height());
        assertEquals(new Slot(0, 100, 24), bar.slots().get(0));
        assertEquals(new Slot(0, 548, 24), bar.slots().get(4));
        assertEquals(new Slot(1, 156, 46), bar.slots().get(5));
        assertEquals(new Slot(1, 492, 46), bar.slots().get(8));
    }

    @Test
    void sevenTabsStayInOneRow() {
        Bar bar = TabBarLayout.arrange(7, 560, 20, 24);

        assertEquals(1, bar.rows());
        assertEquals(7, bar.perRow());
        assertEquals(80, bar.tabWidth());
        assertEquals(20, bar.height());
        assertEquals(new Slot(0, 20, 24), bar.slots().get(0));
        assertEquals(new Slot(0, 500, 24), bar.slots().get(6));
    }

    @Test
    void aNarrowBarTakesAThirdRow() {
        Bar bar = TabBarLayout.arrange(9, 372, 0, 24);

        assertEquals(3, bar.rows());
        assertEquals(3, bar.perRow());
        assertEquals(120, bar.tabWidth());
        assertEquals(64, bar.height());
        assertEquals(new Slot(0, 6, 24), bar.slots().get(0));
        assertEquals(new Slot(1, 6, 46), bar.slots().get(3));
        assertEquals(new Slot(2, 6, 68), bar.slots().get(6));
    }

    @Test
    void aWideContentWidthDoesNotStretchATabPastItsMaximum() {
        Bar bar = TabBarLayout.arrange(2, 900, 0, 0);

        assertEquals(2, bar.perRow());
        assertEquals(TabBarLayout.MAX_TAB_WIDTH, bar.tabWidth());
        assertEquals(330, bar.slots().get(0).x());
    }

    @Test
    void noTabsMakeAnEmptyBar() {
        Bar bar = TabBarLayout.arrange(0, 560, 0, 24);

        assertEquals(0, bar.rows());
        assertTrue(bar.slots().isEmpty());
    }

    @Test
    void everyTabStaysInsideItsContentWidthAndRowsDoNotOverlap() {
        for (int tabCount = 1; tabCount <= 12; tabCount++) {
            for (int contentWidth : new int[] {120, 320, 372, 452, 560}) {
                Bar bar = TabBarLayout.arrange(tabCount, contentWidth, 40, 24);
                String where = tabCount + " tabs at " + contentWidth;

                assertEquals(tabCount, bar.slots().size(), where);
                assertTrue(bar.perRow() * bar.rows() >= tabCount, where);
                assertTrue(bar.tabWidth() > 0 && bar.tabWidth() <= TabBarLayout.MAX_TAB_WIDTH, where);
                List<Slot> slots = bar.slots();
                for (int i = 0; i < slots.size(); i++) {
                    Slot slot = slots.get(i);
                    assertTrue(slot.x() >= 40, where + ", tab " + i);
                    assertTrue(slot.x() + bar.tabWidth() <= 40 + contentWidth, where + ", tab " + i);
                    assertEquals(24 + slot.row() * (TabBarLayout.TAB_HEIGHT + TabBarLayout.TAB_GAP), slot.y(), where);
                    if (i % bar.perRow() != 0) {
                        assertEquals(slots.get(i - 1).x() + bar.tabWidth(), slot.x(), where + ", tab " + i);
                    }
                }
            }
        }
    }
}
