package dev.friendai.features.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdleRefreshLedgerTest {
    private static final UUID TRADER = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);

    @Test
    void aTraderNeverVisitedIsDue() {
        assertTrue(new IdleRefreshLedger().due(TRADER, 100));
    }

    @Test
    void aVisitedTraderWaitsTheWholeRetryWindow() {
        IdleRefreshLedger ledger = new IdleRefreshLedger();
        ledger.attempted(TRADER, 1_000);

        assertFalse(ledger.due(TRADER, 1_000 + 200), "the next refresh interval must not revisit it");
        assertFalse(ledger.due(TRADER, 1_000 + IdleRefreshLedger.RETRY_TICKS - 1));
        assertTrue(ledger.due(TRADER, 1_000 + IdleRefreshLedger.RETRY_TICKS));
        assertTrue(ledger.due(OTHER, 1_200), "another trader keeps its own clock");
    }

    @Test
    void aClockThatMovedBackwardsMakesTheTraderDue() {
        IdleRefreshLedger ledger = new IdleRefreshLedger();
        ledger.attempted(TRADER, 5_000);

        assertTrue(ledger.due(TRADER, 10));
    }

    @Test
    void entriesThatAreDueAgainArePruned() {
        IdleRefreshLedger ledger = new IdleRefreshLedger();
        ledger.attempted(TRADER, 0);
        ledger.attempted(OTHER, IdleRefreshLedger.RETRY_TICKS);

        assertEquals(1, ledger.size());
        assertTrue(ledger.due(TRADER, IdleRefreshLedger.RETRY_TICKS));
    }

    @Test
    void theLedgerKeepsAtMostItsBoundAndDropsTheOldestFirst() {
        IdleRefreshLedger ledger = new IdleRefreshLedger();
        UUID first = new UUID(1, 0);
        ledger.attempted(first, 0);
        for (int i = 1; i <= IdleRefreshLedger.MAX_TRADERS; i++) ledger.attempted(new UUID(1, i), i);

        assertEquals(IdleRefreshLedger.MAX_TRADERS, ledger.size());
        assertTrue(ledger.due(first, IdleRefreshLedger.MAX_TRADERS), "the oldest entry made room");
        assertFalse(ledger.due(new UUID(1, IdleRefreshLedger.MAX_TRADERS), IdleRefreshLedger.MAX_TRADERS));
    }
}
