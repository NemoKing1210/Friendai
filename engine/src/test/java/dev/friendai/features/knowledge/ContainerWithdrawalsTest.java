package dev.friendai.features.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.spatial.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContainerWithdrawalsTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String IRON = "minecraft:iron_ingot";

    @Test
    void aLiveFarContainerBeatsARememberedNearOne() {
        var observations =
                List.of(observation(1, 0, 0, 0, Map.of(IRON, 9)), observation(8, 0, 0, 9_500, Map.of(IRON, 2)));
        var ranked = ContainerWithdrawals.rank(observations, IRON::equals, OVERWORLD, 0, 64, 0, 10_000, 16);
        assertEquals(8, ranked.getFirst().location().pos().x());
        assertTrue(ranked.getFirst().live());
    }

    @Test
    void theNearerContainerWinsInsideOneFreshnessTier() {
        var observations =
                List.of(observation(8, 0, 0, 9_500, Map.of(IRON, 9)), observation(3, 0, 0, 9_500, Map.of(IRON, 2)));
        var ranked = ContainerWithdrawals.rank(observations, IRON::equals, OVERWORLD, 0, 64, 0, 10_000, 16);
        assertEquals(3, ranked.getFirst().location().pos().x());
    }

    @Test
    void moreBelievedStockBreaksADistanceTie() {
        var observations =
                List.of(observation(2, 0, 0, 9_500, Map.of(IRON, 3)), observation(-2, 0, 0, 9_500, Map.of(IRON, 7)));
        var ranked = ContainerWithdrawals.rank(observations, IRON::equals, OVERWORLD, 0, 64, 0, 10_000, 16);
        assertEquals(-2, ranked.getFirst().location().pos().x());
        assertEquals(7, ranked.getFirst().count());
    }

    @Test
    void otherDimensionsStaleRecordsEmptyRecordsAndFarContainersAreNotCandidates() {
        var observations = List.of(
                new ResourceKnowledge.ContainerObservation(
                        new ResourceObservation.Location("minecraft:the_nether", new Pos(1, 64, 0)),
                        Map.of(IRON, 5),
                        30_000),
                observation(1, 0, 0, 0, Map.of(IRON, 5)),
                observation(2, 0, 0, 30_000, Map.of("minecraft:stone", 5)),
                observation(10, 0, 0, 30_000, Map.of(IRON, 5)));
        var ranked = ContainerWithdrawals.rank(observations, IRON::equals, OVERWORLD, 0, 64, 0, 30_000, 5);
        assertTrue(ranked.isEmpty(), "unexpected candidates: " + ranked);
    }

    @Test
    void theListIsBounded() {
        List<ResourceKnowledge.ContainerObservation> observations = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            observations.add(observation(index, 0, 0, 9_500, Map.of(IRON, 1)));
        }
        var ranked = ContainerWithdrawals.rank(observations, IRON::equals, OVERWORLD, 0, 64, 0, 10_000, 32);
        assertEquals(ContainerWithdrawals.MAX_CANDIDATES, ranked.size());
    }

    @Test
    void missingInputsRankNothing() {
        assertTrue(ContainerWithdrawals.rank(List.of(), IRON::equals, OVERWORLD, 0, 64, 0, 10_000, 16)
                .isEmpty());
    }

    private static ResourceKnowledge.ContainerObservation observation(
            int x, int y, int z, long tick, Map<String, Integer> items) {
        return new ResourceKnowledge.ContainerObservation(
                new ResourceObservation.Location(OVERWORLD, new Pos(x, 64 + y, z)), items, tick);
    }
}
