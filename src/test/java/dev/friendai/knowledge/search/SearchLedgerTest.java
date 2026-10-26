package dev.friendai.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.friendai.pathing.Pos;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchLedgerTest {
    @Test
    void sameGoalRetryWithinCooldownReusesSpentBudget() {
        SearchLedger ledger = new SearchLedger();
        UUID owner = UUID.randomUUID();
        var first = ledger.acquire(owner, "minecraft:overworld", "  Iron  Ore ", 100,
                SearchLedger.LOCAL_DEFAULTS, new Pos(4, 64, 8));
        first.advance(new SearchSession.Input(110, "minecraft:overworld", new Pos(4, 64, 8), true, false,
                SearchSession.LegOutcome.NONE, null, 0, 0, 0, 0));
        var retry = ledger.acquire(owner, "minecraft:overworld", "iron ore", 120,
                new SearchSession.Limits(512, 2_048, 12_000, 128), new Pos(40, 64, 80));
        assertSame(first, retry);
        assertEquals(10, retry.snapshot().spentTicks());
        assertEquals(SearchLedger.LOCAL_DEFAULTS, retry.snapshot().limits());
    }

    @Test
    void ledgerSnapshotRetainsActiveSessionBudget() {
        SearchLedger original = new SearchLedger();
        UUID owner = UUID.randomUUID();
        var session = original.acquire(owner, "minecraft:overworld", "iron", 100,
                SearchLedger.LOCAL_DEFAULTS, new Pos(4, 64, 8));
        session.advance(new SearchSession.Input(110, "minecraft:overworld", new Pos(4, 64, 8), true, false,
                SearchSession.LegOutcome.NONE, null, 0, 0, 0, 0));
        SearchLedger restored = new SearchLedger();
        restored.restore(original.toJson());
        assertEquals(10, restored.acquire(owner, "minecraft:overworld", "iron", 120,
                SearchLedger.LOCAL_DEFAULTS, new Pos(0, 64, 0)).snapshot().spentTicks());
    }
}
