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
    void changeListenerFiresForRealMutationsOnly() {
        TrailGraph graph = new TrailGraph();
        int[] changes = {0};
        graph.onChange(() -> changes[0]++);

        var ids = graph.recordSegment(
                "minecraft:overworld", List.of(new Pos(0, 64, 0), new Pos(8, 64, 0), new Pos(16, 64, 0)), 40, false);
        assertEquals(1, changes[0], "a recorded segment signals one change");
        graph.invalidate(ids.getFirst());
        assertEquals(2, changes[0], "an invalidation signals one change");
        graph.invalidate(ids.getFirst());
        assertEquals(2, changes[0], "invalidating an already invalid edge changes nothing");
        graph.invalidate(9_999);
        assertEquals(2, changes[0], "an unknown edge changes nothing");
        assertEquals(
                List.of(),
                graph.recordSegment("minecraft:overworld", List.of(new Pos(0, 64, 0), new Pos(0, 64, 0)), 0, false));
        assertEquals(2, changes[0], "a recording without segments changes nothing");

        TrailGraph restored = TrailGraph.fromJson(graph.toJson());
        int[] restoredChanges = {0};
        restored.onChange(() -> restoredChanges[0]++);
        assertEquals(0, restoredChanges[0], "restoration must not report a mutation");
        restored.invalidate(ids.get(1));
        assertEquals(1, restoredChanges[0], "the restored graph signals later mutations");
    }

    @Test
    void routeKeepsItsTieBreakAfterInvalidationAndRestore() {
        TrailGraph graph = new TrailGraph();
        var first = graph.recordSegment("minecraft:overworld", List.of(new Pos(0, 64, 0), new Pos(8, 64, 0)), 20, true);
        graph.recordSegment("minecraft:overworld", List.of(new Pos(0, 64, 0), new Pos(8, 64, 0)), 20, false);
        List<Pos> expected = List.of(new Pos(0, 64, 0), new Pos(8, 64, 0));

        assertEquals(expected, graph.route("minecraft:overworld", new Pos(0, 64, 0), new Pos(8, 64, 0)));
        graph.invalidate(first.getFirst());
        assertEquals(
                expected,
                graph.route("minecraft:overworld", new Pos(0, 64, 0), new Pos(8, 64, 0)),
                "the parallel edge keeps the route alive");
        TrailGraph restored = TrailGraph.fromJson(graph.toJson());
        assertEquals(
                expected,
                restored.route("minecraft:overworld", new Pos(0, 64, 0), new Pos(8, 64, 0)),
                "restored adjacency keeps the same deterministic route");
        assertTrue(
                restored.route("minecraft:overworld", new Pos(8, 64, 0), new Pos(0, 64, 0))
                                .size()
                        == 2,
                "reversed edges survive the restore as well");
    }

    @Test
    void graphCapsStayBoundedAndRoutingStillWorks() {
        TrailGraph graph = new TrailGraph();
        for (int i = 0; i < 3_000; i++) {
            graph.recordSegment(
                    "minecraft:overworld", List.of(new Pos(i * 8, 64, 0), new Pos((i + 1) * 8, 64, 0)), 8, false);
        }
        assertTrue(graph.nodeCount() <= 1_024, "node cap: " + graph.nodeCount());
        assertTrue(graph.edgeCount() <= 2_048, "edge cap: " + graph.edgeCount());
        assertEquals(
                List.of(new Pos(0, 64, 0), new Pos(8, 64, 0), new Pos(16, 64, 0)),
                graph.route("minecraft:overworld", new Pos(0, 64, 0), new Pos(16, 64, 0)),
                "capped adjacency still routes along recorded edges");
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
