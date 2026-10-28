package dev.friendai.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObservationWindowTest {
    private static ResourceObservation observation(String block, int x, long tick) {
        return new ResourceObservation(
                block,
                new ResourceObservation.Location("minecraft:overworld", new Pos(x, 64, 0)),
                new Pos(0, 64, 0),
                tick);
    }

    private static List<String> ids(List<ResourceObservation> observations) {
        return observations.stream().map(ResourceObservation::blockId).toList();
    }

    @Test
    void recentRespectsAgeAndCapacity() {
        ObservationWindow window = new ObservationWindow(2, 10);
        window.record(observation("minecraft:oak_log", 0, 100));
        window.record(observation("minecraft:acacia_log", 1, 105));
        assertEquals(2, window.recent(105).size());

        window.record(observation("minecraft:birch_log", 2, 116));
        assertEquals(List.of("minecraft:birch_log"), ids(window.recent(116)));
        assertTrue(window.recent(200).isEmpty());
    }

    @Test
    void capacityEvictsTheOldest() {
        ObservationWindow window = new ObservationWindow(1, 100);
        window.record(observation("minecraft:oak_log", 0, 100));
        window.record(observation("minecraft:birch_log", 1, 101));

        assertEquals(List.of("minecraft:birch_log"), ids(window.recent(101)));
    }

    @Test
    void recordReplacesTheSameLocation() {
        ObservationWindow window = new ObservationWindow(2, 100);
        window.record(observation("minecraft:oak_log", 0, 100));
        window.record(observation("minecraft:acacia_log", 0, 101));

        assertEquals(1, window.size());
        assertEquals(List.of("minecraft:acacia_log"), ids(window.recent(101)));
    }

    @Test
    void forgetRemovesOnlyThatLocation() {
        ObservationWindow window = new ObservationWindow(2, 100);
        window.record(observation("minecraft:oak_log", 0, 100));
        window.record(observation("minecraft:acacia_log", 1, 100));

        assertTrue(window.forget(new ResourceObservation.Location("minecraft:overworld", new Pos(0, 64, 0))));
        assertFalse(window.forget(new ResourceObservation.Location("minecraft:overworld", new Pos(9, 64, 0))));
        assertEquals(List.of("minecraft:acacia_log"), ids(window.recent(100)));
    }
}
