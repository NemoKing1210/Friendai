package dev.friendai.features.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.foundation.spatial.Pos;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchLedgerTest {
    private static final Pos BASE = new Pos(4, 64, 8);
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void anInterruptedTripFromTheSameOriginKeepsItsSpentBudget() {
        SearchLedger ledger = new SearchLedger();
        UUID owner = UUID.randomUUID();
        var first = ledger.acquire(owner, DIMENSION, "  Iron  Ore ", 100, SearchLedger.LOCAL_DEFAULTS, BASE);
        assertFalse(first.resumed());
        step(first.session(), 110, BASE);
        ledger.interruptActive();

        var retry = ledger.acquire(owner, DIMENSION, "iron ore", 120, SearchLedger.LOCAL_DEFAULTS, BASE);

        assertTrue(retry.resumed());
        assertSame(first.session(), retry.session());
        assertEquals(1, retry.session().snapshot().spentTicks());
        assertEquals(SearchLedger.LOCAL_DEFAULTS, retry.session().snapshot().limits());
        assertEquals(BASE, retry.session().snapshot().origin());
    }

    @Test
    void aRequestFromANewBaseStartsWithAFreshSession() {
        SearchLedger ledger = new SearchLedger();
        UUID owner = UUID.randomUUID();
        var done = ledger.acquire(owner, DIMENSION, "iron", 100, SearchLedger.LOCAL_DEFAULTS, BASE);
        step(done.session(), 110, BASE);
        finish(done.session(), 120, BASE);
        assertEquals(SearchSession.Phase.COMPLETE, done.session().snapshot().phase());

        var moved = ledger.acquire(owner, DIMENSION, "iron", 130, SearchLedger.LOCAL_DEFAULTS, new Pos(40, 64, 80));

        assertFalse(moved.resumed());
        assertNotEquals(
                done.session().snapshot().id(), moved.session().snapshot().id());
        assertEquals(SearchSession.Phase.PREPARE, moved.session().snapshot().phase());
        assertEquals(0, moved.session().snapshot().spentTicks());
        assertEquals(new Pos(40, 64, 80), moved.session().snapshot().origin());
    }

    @Test
    void aWiderAuthorizedScopeStartsANewSearch() {
        SearchLedger ledger = new SearchLedger();
        UUID owner = UUID.randomUUID();
        var local = ledger.acquire(owner, DIMENSION, "iron", 100, SearchLedger.LOCAL_DEFAULTS, BASE);
        step(local.session(), 110, BASE);
        ledger.interruptActive();

        var expedition =
                ledger.acquire(owner, DIMENSION, "iron", 120, new SearchSession.Limits(512, 2_048, 12_000, 128), BASE);

        assertFalse(expedition.resumed());
        assertNotEquals(
                local.session().snapshot().id(), expedition.session().snapshot().id());
        assertEquals(0, expedition.session().snapshot().spentTicks());
        assertEquals(512, expedition.session().snapshot().limits().radius());
    }

    @Test
    void differentTargetSetsWithinOneFamilyDoNotShareASession() {
        SearchLedger ledger = new SearchLedger();
        UUID owner = UUID.randomUUID();
        var logs = ledger.acquire(
                owner,
                DIMENSION,
                "wood{minecraft:birch_log,minecraft:oak_log}",
                100,
                SearchLedger.LOCAL_DEFAULTS,
                BASE);
        step(logs.session(), 110, BASE);
        ledger.interruptActive();

        var oak = ledger.acquire(owner, DIMENSION, "wood{minecraft:oak_log}", 120, SearchLedger.LOCAL_DEFAULTS, BASE);

        assertFalse(oak.resumed());
        assertNotEquals(logs.session().snapshot().id(), oak.session().snapshot().id());
    }

    @Test
    void ledgerSnapshotRetainsAnInterruptedBudgetForTheSameOrigin() {
        SearchLedger original = new SearchLedger();
        UUID owner = UUID.randomUUID();
        var session = original.acquire(owner, DIMENSION, "iron", 100, SearchLedger.LOCAL_DEFAULTS, BASE);
        step(session.session(), 110, BASE);
        original.interruptActive();
        SearchLedger restored = new SearchLedger();
        restored.restore(original.toJson());

        var retry = restored.acquire(owner, DIMENSION, "iron", 120, SearchLedger.LOCAL_DEFAULTS, BASE);

        assertTrue(retry.resumed());
        assertEquals(1, retry.session().snapshot().spentTicks());
    }

    @Test
    void resumeByIdKeepsTheSpentBudgetAndTheReturnPhase() {
        SearchLedger ledger = new SearchLedger();
        UUID owner = UUID.randomUUID();
        var session = ledger.acquire(owner, DIMENSION, "iron", 100, SearchLedger.LOCAL_DEFAULTS, BASE)
                .session();
        session.advance(new SearchSession.Input(
                110, DIMENSION, new Pos(40, 64, 8), true, true, SearchSession.LegOutcome.NONE, null, 0, 0, 0, 0));
        long spentTicks = session.snapshot().spentTicks();
        long spentTravel = Math.round(session.snapshot().spentTravelBlocks());
        ledger.interruptActive();

        var resumed = ledger.resume(session.snapshot().id(), owner, DIMENSION, "  Iron ", 200);

        assertSame(session, resumed);
        assertEquals(SearchSession.Phase.RETURN, resumed.snapshot().phase());
        assertTrue(resumed.snapshot().discovered());
        assertEquals(spentTicks, resumed.snapshot().spentTicks());
        assertEquals(spentTravel, Math.round(resumed.snapshot().spentTravelBlocks()));
        assertEquals(BASE, resumed.snapshot().origin());
    }

    @Test
    void resumeRefusesAnotherGoalOwnerOrADeliberatelyStoppedSession() {
        SearchLedger ledger = new SearchLedger();
        UUID owner = UUID.randomUUID();
        var session = ledger.acquire(owner, DIMENSION, "iron", 100, SearchLedger.LOCAL_DEFAULTS, BASE)
                .session();
        ledger.interruptActive();
        UUID id = session.snapshot().id();

        assertNull(ledger.resume(id, owner, DIMENSION, "copper", 120));
        assertNull(ledger.resume(id, UUID.randomUUID(), DIMENSION, "iron", 120));
        assertNull(ledger.resume(UUID.randomUUID(), owner, DIMENSION, "iron", 120));

        SearchSession stopped = ledger.acquire(owner, DIMENSION, "gold", 110, SearchLedger.LOCAL_DEFAULTS, BASE)
                .session();
        stopped.interrupt();
        assertFalse(stopped.resumable());
        assertNull(ledger.resume(stopped.snapshot().id(), owner, DIMENSION, "gold", 120));
    }

    @Test
    void aLegacyRowWithoutTheReloadMarkerStillLoads() {
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        JsonObject row = new JsonObject();
        row.addProperty("owner", owner.toString());
        row.addProperty("dimension", DIMENSION);
        row.addProperty("goal", "iron");
        row.addProperty("id", id.toString());
        row.addProperty("x", 4);
        row.addProperty("y", 64);
        row.addProperty("z", 8);
        row.addProperty("radius", 64);
        row.addProperty("travel", 256);
        row.addProperty("limit_ticks", 2_400);
        row.addProperty("excavation", 128);
        row.addProperty("phase", "INTERRUPTED");
        row.addProperty("started", 100);
        row.addProperty("last", 100);
        row.addProperty("spent_travel", 10.0);
        row.addProperty("spent_ticks", 7);
        row.addProperty("excavated", 2);
        row.addProperty("blocked", 0);

        SearchLedger restored = new SearchLedger();
        restored.restore(List.of(row));
        SearchSession session = restored.session(id);

        assertNotNull(session);
        assertEquals(SearchSession.Phase.INTERRUPTED, session.snapshot().phase());
        assertFalse(session.snapshot().discovered());
        assertFalse(session.resumable());
        assertNull(restored.resume(id, owner, DIMENSION, "iron", 200));
        // A row this version cannot resume is spent work, not a session a new order may continue.
        SearchLedger.Admission fresh =
                restored.acquire(owner, DIMENSION, "iron", 200, SearchLedger.LOCAL_DEFAULTS, BASE);
        assertFalse(fresh.resumed());
        assertNotEquals(id, fresh.session().snapshot().id());
        assertEquals(0, fresh.session().snapshot().spentTicks());
    }

    private static void step(SearchSession session, long tick, Pos position) {
        session.advance(new SearchSession.Input(
                tick, DIMENSION, position, true, false, SearchSession.LegOutcome.NONE, null, 0, 0, 0, 0));
    }

    private static void finish(SearchSession session, long tick, Pos origin) {
        session.advance(new SearchSession.Input(
                tick, DIMENSION, origin, true, true, SearchSession.LegOutcome.NONE, null, 0, 0, 0, 0));
        session.advance(new SearchSession.Input(
                tick + 1, DIMENSION, origin, true, false, SearchSession.LegOutcome.NONE, null, 0, 0, 0, 0));
        assertTrue(session.terminal(), "the trip reaches a terminal phase at its origin");
    }
}
