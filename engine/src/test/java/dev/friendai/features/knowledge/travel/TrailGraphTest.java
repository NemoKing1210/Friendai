package dev.friendai.features.knowledge.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrailGraphTest {
    @Test
    void onlyExecutedSegmentsConnectRoute() {
        TrailGraph graph = new TrailGraph();
        graph.recordSegment(
                "minecraft:overworld", List.of(new Pos(0, 64, 0), new Pos(8, 64, 0), new Pos(16, 64, 0)), 40, true);
        assertEquals(
                List.of(new Pos(0, 64, 0), new Pos(8, 64, 0), new Pos(16, 64, 0)),
                graph.route("minecraft:overworld", new Pos(0, 64, 0), new Pos(16, 64, 0)));
        assertTrue(graph.route("minecraft:the_nether", new Pos(0, 64, 0), new Pos(16, 64, 0))
                .isEmpty());
    }

    @Test
    void reverseRouteRequiresValidationAndInvalidEdgesAreRemoved() {
        TrailGraph graph = new TrailGraph();
        var ids = graph.recordSegment("minecraft:overworld", List.of(new Pos(0, 64, 0), new Pos(8, 64, 0)), 20, false);
        assertTrue(graph.route("minecraft:overworld", new Pos(8, 64, 0), new Pos(0, 64, 0))
                .isEmpty());
        graph.invalidate(ids.get(0));
        assertTrue(graph.route("minecraft:overworld", new Pos(0, 64, 0), new Pos(8, 64, 0))
                .isEmpty());
    }

    @Test
    void executedTurnIsRetainedAsWaypoint() {
        TrailGraph graph = new TrailGraph();
        graph.recordSegment(
                "minecraft:overworld", List.of(new Pos(0, 64, 0), new Pos(4, 64, 0), new Pos(4, 64, 4)), 40, false);
        assertEquals(
                List.of(new Pos(0, 64, 0), new Pos(4, 64, 0), new Pos(4, 64, 4)),
                graph.route("minecraft:overworld", new Pos(0, 64, 0), new Pos(4, 64, 4)));
    }

    @Test
    void nodeProximityIsDimensionScopedAndBounded() {
        TrailGraph graph = new TrailGraph();
        graph.recordSegment("minecraft:overworld", List.of(new Pos(0, 64, 0), new Pos(8, 64, 0)), 20, false);
        assertTrue(graph.hasNodeNear("minecraft:overworld", new Pos(2, 64, 0), 4));
        assertTrue(!graph.hasNodeNear("minecraft:overworld", new Pos(2, 64, 0), 1));
        assertTrue(!graph.hasNodeNear("minecraft:the_nether", new Pos(2, 64, 0), 8));
    }
}
