package dev.friendai.foundation.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MaterialReserveTest {
    private static final String COBBLE = "minecraft:cobblestone";
    private static final String DIRT = "minecraft:dirt";

    @Test
    void aReservedStackIsNotSpendableUntilTheReserveIsCovered() {
        MaterialReserve reserve = MaterialReserve.of(Map.of(COBBLE, 8));
        assertFalse(reserve.spendable(COBBLE, 8));
        assertFalse(reserve.spendable(COBBLE, 3));
        assertEquals(0, reserve.free(COBBLE, 8));
        assertTrue(reserve.spendable(COBBLE, 9));
        assertEquals(1, reserve.free(COBBLE, 9));
    }

    @Test
    void aHeldCountBelowTheReserveNeverGoesNegative() {
        assertEquals(0, MaterialReserve.of(Map.of(COBBLE, 8)).free(COBBLE, 2));
        assertEquals(0, MaterialReserve.of(Map.of(COBBLE, 8)).free(COBBLE, -4));
    }

    @Test
    void oneItemSpentLeavesTheOtherItemsAlone() {
        MaterialReserve reserve = MaterialReserve.of(Map.of(COBBLE, 2, DIRT, 1));
        MaterialReserve after = reserve.spend(COBBLE);
        assertEquals(1, after.reserved(COBBLE));
        assertEquals(1, after.reserved(DIRT));
        assertEquals(2, reserve.reserved(COBBLE), "the reserve is immutable");
        assertTrue(after.spend(COBBLE).spend(DIRT).isEmpty(), "a fully spent reserve reserves nothing");
    }

    @Test
    void composingKeepsTheLargerNeedOfTheTwoSources() {
        MaterialReserve composed = MaterialReserve.compose(Map.of(COBBLE, 4, DIRT, 2), Map.of(COBBLE, 6));
        assertEquals(6, composed.reserved(COBBLE), "the running task already mines part of the plan's material");
        assertEquals(2, composed.reserved(DIRT));
        assertEquals(6, composed.asMap().get(COBBLE));
    }

    @Test
    void returnSupportIsAddedBesideWorkAndReleasedOnlyForTheReturnLeg() {
        MaterialReserve composed =
                MaterialReserve.compose(Map.of(COBBLE, 2), Map.of(COBBLE, 3), Map.of(COBBLE, 2, DIRT, 1));

        assertEquals(5, composed.reserved(COBBLE));
        assertEquals(3, composed.workReserved(COBBLE));
        assertEquals(2, composed.travelReserved(COBBLE));
        assertEquals(1, composed.reserved(DIRT));
        assertEquals(Map.of(COBBLE, 3), composed.workAsMap());
        assertEquals(Map.of(COBBLE, 2, DIRT, 1), composed.travelAsMap());
        assertEquals(3, composed.forReturnLeg().reserved(COBBLE));
        assertEquals(0, composed.forReturnLeg().travelReserved(COBBLE));
        assertEquals(0, composed.forReturnLeg().reserved(DIRT));
    }

    @Test
    void returnOnlyShareRemainsSpendableOnTheReturnLeg() {
        MaterialReserve composed = MaterialReserve.compose(Map.of(), Map.of(), Map.of(DIRT, 1));

        assertEquals(1, composed.travelReserved(DIRT));
        assertEquals(0, composed.forReturnLeg().reserved(DIRT));
        assertTrue(composed.forReturnLeg().spendable(DIRT, 1));
    }

    @Test
    void spareSupportCountSubtractsTheExistingWorkReserve() {
        MaterialReserve reserve = MaterialReserve.compose(Map.of(COBBLE, 2), Map.of(COBBLE, 3), Map.of());

        int spare = reserve.free(COBBLE, 4);

        assertEquals(1, spare);
    }

    @Test
    void entriesThatReserveNothingAreDropped() {
        assertTrue(MaterialReserve.of(Map.of(COBBLE, 0, DIRT, -2, "", 5)).isEmpty());
        assertTrue(MaterialReserve.EMPTY.isEmpty());
        assertEquals(0, MaterialReserve.EMPTY.reserved(COBBLE));
        assertTrue(MaterialReserve.EMPTY.spendable(COBBLE, 1), "an empty reserve never blocks a placement");
    }
}
