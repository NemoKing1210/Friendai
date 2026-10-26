package dev.friendai.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.pathing.Pos;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchSessionTest {
    @Test
    void localDefaultsMatchPolicy() {
        assertEquals(new SearchSession.Limits(64, 256, 2400, 128), SearchLedger.LOCAL_DEFAULTS);
    }

    @Test
    void reservesReturnBudgetBeforeStartingOutboundLeg() {
        SearchSession session = new SearchSession(UUID.randomUUID(), "minecraft:overworld", "iron",
                new Pos(0, 64, 0), new SearchSession.Limits(64, 256, 2400, 128), 0);
        var ready = new SearchSession.Input(1, "minecraft:overworld", new Pos(0, 64, 0), true, false,
                SearchSession.LegOutcome.NONE, null, 0, 0, 0, 0);
        session.advance(ready);
        var frontier = new SearchPlanner.Frontier(new Pos(20, 64, 0), new Pos(20, 64, 0),
                ResourceSearchProfile.Strategy.SURFACE, 4, 1000);
        var decision = session.advance(new SearchSession.Input(2, "minecraft:overworld", new Pos(0, 64, 0), true,
                false, SearchSession.LegOutcome.NONE, frontier, 0, 0, 0, 1500));
        assertEquals(SearchSession.Phase.RETURN, decision.phase());
        assertEquals(SearchSession.Reason.BUDGET_RESERVE, decision.reason());
    }
}
