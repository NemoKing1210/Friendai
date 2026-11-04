package dev.friendai.features.cartography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WalkRoadAccumulatorTest {
    @Test
    void manyShortStepsAccumulateIntoRouteSegments() {
        WalkRoadAccumulator walk = new WalkRoadAccumulator(16);
        List<WalkRoadAccumulator.Segment> segments = new ArrayList<>();
        for (int step = 0; step <= 25; step++) {
            walk.observe("minecraft:overworld", step * 4, 0);
            walk.take().ifPresent(segments::add);
        }
        assertTrue(segments.size() >= 6, "a hundred walked blocks must be recorded, got " + segments);
        for (WalkRoadAccumulator.Segment segment : segments) {
            int dx = segment.toX() - segment.fromX();
            int dz = segment.toZ() - segment.fromZ();
            assertTrue(
                    Math.sqrt((double) dx * dx + (double) dz * dz) >= 16.0,
                    "no segment shorter than the minimum: " + segment);
        }
        assertEquals(0, segments.get(0).fromX(), "the route starts where the walk started");
        assertEquals(96, segments.get(segments.size() - 1).toX(), "the route reaches the last recorded end");
    }

    @Test
    void aShortErrandIsNotARoute() {
        WalkRoadAccumulator walk = new WalkRoadAccumulator(16);
        walk.observe("minecraft:overworld", 0, 0);
        walk.observe("minecraft:overworld", 3, 0);
        walk.observe("minecraft:overworld", 6, 0);
        assertTrue(walk.take().isEmpty());
    }

    @Test
    void aDimensionChangeStartsANewRoute() {
        WalkRoadAccumulator walk = new WalkRoadAccumulator(16);
        walk.observe("minecraft:overworld", 0, 0);
        walk.observe("minecraft:the_nether", 100, 0);
        assertTrue(walk.take().isEmpty(), "a dimension change is not a walked segment");
        walk.observe("minecraft:the_nether", 120, 0);
        WalkRoadAccumulator.Segment segment = walk.take().orElseThrow();
        assertEquals("minecraft:the_nether", segment.dimension());
        assertEquals(100, segment.fromX(), "the new route starts at the arrival point");
        assertEquals(120, segment.toX());
    }
}
