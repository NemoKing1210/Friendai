package dev.friendai.features.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** A crafting session spans several tasks, so a placed station is leased for a while instead of taken back at once. */
class StationLeaseTest {
    private static final long TABLE = 0x1234L;

    @Test
    void keepsAPlacedStationForTheGracePeriod() {
        StationLease lease = new StationLease();
        lease.keep(Station.CRAFTING_TABLE, TABLE, 100);
        assertEquals(OptionalLong.of(TABLE), lease.held(Station.CRAFTING_TABLE, 100), "right after placing");
        assertEquals(
                OptionalLong.of(TABLE),
                lease.held(Station.CRAFTING_TABLE, 100 + StationLease.GRACE_TICKS - 1),
                "one tick before the grace runs out");
        assertEquals(
                OptionalLong.empty(),
                lease.held(Station.CRAFTING_TABLE, 100 + StationLease.GRACE_TICKS),
                "the grace has run out");
    }

    @Test
    void reusingAStationExtendsItsGrace() {
        StationLease lease = new StationLease();
        lease.keep(Station.CRAFTING_TABLE, TABLE, 100);
        lease.keep(Station.CRAFTING_TABLE, TABLE, 500);
        assertEquals(OptionalLong.of(TABLE), lease.held(Station.CRAFTING_TABLE, 1000), "the second craft renewed it");
    }

    @Test
    void aStationIsDueOnceItsGraceRanOut() {
        StationLease lease = new StationLease();
        lease.keep(Station.CRAFTING_TABLE, TABLE, 100);
        assertTrue(lease.due(100 + StationLease.GRACE_TICKS - 1).isEmpty(), "still leased");
        assertEquals(
                new StationLease.Due(Station.CRAFTING_TABLE, TABLE),
                lease.due(100 + StationLease.GRACE_TICKS).orElseThrow(),
                "due once the grace ran out");
        assertEquals(
                new StationLease.Due(Station.CRAFTING_TABLE, TABLE),
                lease.due(1000).orElseThrow(),
                "due stays due until it is released, so a missed sweep is retried");
    }

    @Test
    void releaseForgetsTheStation() {
        StationLease lease = new StationLease();
        lease.keep(Station.CRAFTING_TABLE, TABLE, 100);
        lease.release(Station.CRAFTING_TABLE);
        assertEquals(OptionalLong.empty(), lease.held(Station.CRAFTING_TABLE, 200), "released");
        assertTrue(lease.due(2000).isEmpty(), "nothing to fetch back");
        assertTrue(lease.isEmpty(), "empty");
    }

    @Test
    void aSnapshotCarriesEveryLeaseWithItsDeadline() {
        StationLease original = new StationLease();
        original.keep(Station.CRAFTING_TABLE, TABLE, 100);
        original.keep(Station.FURNACE, 0x99L, 200);

        StationLease copy = new StationLease();
        copy.restore(original.snapshot());

        assertEquals(OptionalLong.of(TABLE), copy.held(Station.CRAFTING_TABLE, 300), "table keeps its deadline");
        assertEquals(OptionalLong.of(0x99L), copy.held(Station.FURNACE, 300), "furnace keeps its deadline");
        assertTrue(copy.due(100 + StationLease.GRACE_TICKS - 1).isEmpty(), "the copy is not due before its deadline");
        assertEquals(
                new StationLease.Due(Station.CRAFTING_TABLE, TABLE),
                copy.due(100 + StationLease.GRACE_TICKS).orElseThrow(),
                "the earlier lease is due first after the copy");
    }

    @Test
    void oneLeasePerStation() {
        StationLease lease = new StationLease();
        lease.keep(Station.CRAFTING_TABLE, 7L, 100);
        lease.keep(Station.CRAFTING_TABLE, 9L, 100);
        assertEquals(OptionalLong.of(9L), lease.held(Station.CRAFTING_TABLE, 200), "the newest placement wins");
        lease.release(Station.CRAFTING_TABLE);
        assertFalse(lease.due(2000).isPresent(), "the older placement must not come back");
    }
}
