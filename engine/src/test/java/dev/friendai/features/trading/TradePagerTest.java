package dev.friendai.features.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TradePagerTest {
    @Test
    void turningAdvancesAndClampsWithoutResetting() {
        TradePager pager = new TradePager();
        pager.turn(1, 5);
        assertEquals(1, pager.page(), "next must ask for the second page");
        pager.turn(1, 5);
        assertEquals(2, pager.page());
        pager.turn(-1, 5);
        assertEquals(1, pager.page());
        pager.turn(9, 5);
        assertEquals(4, pager.page(), "the last page is the highest the list has");
        pager.turn(-9, 5);
        assertEquals(0, pager.page());
    }

    @Test
    void aChangedFilterStartsAgainAtTheFirstPage() {
        TradePager pager = new TradePager();
        pager.turn(1, 5);
        pager.filterChanged();
        assertEquals(0, pager.page());
    }

    @Test
    void anEmptyResultHasOnlyTheFirstPage() {
        TradePager pager = new TradePager();
        pager.turn(1, 0);
        assertEquals(0, pager.page());
        pager.turn(-1, 0);
        assertEquals(0, pager.page());
    }
}
