package dev.friendai.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuildOrderTest {
    private static final Set<String> SUPPORTED = Set.of("minecraft:wall_torch", "minecraft:torch", "minecraft:oak_door", "minecraft:ladder");

    /** A spec is an attachment when its id has one of these words; production passes the same kind of test. */
    private static boolean needsSupport(String state) {
        String id = state.substring(0, state.indexOf('[') < 0 ? state.length() : state.indexOf('['));
        return SUPPORTED.contains(id) && !state.contains("half=upper");
    }

    private static Blueprint blueprint(BlockSpec... specs) {
        int maxX = 0;
        int maxY = 0;
        int maxZ = 0;
        for (BlockSpec spec : specs) {
            maxX = Math.max(maxX, spec.x());
            maxY = Math.max(maxY, spec.y());
            maxZ = Math.max(maxZ, spec.z());
        }
        return new Blueprint("test", maxX + 1, maxY + 1, maxZ + 1, List.of(specs));
    }

    private static List<String> states(List<BlockSpec> ordered) {
        return ordered.stream().map(s -> s.x() + "," + s.y() + "," + s.z()).toList();
    }

    @Test
    void layersBottomUp() {
        Blueprint bp = blueprint(
                new BlockSpec(0, 2, 0, "minecraft:stone"),
                new BlockSpec(0, 0, 0, "minecraft:stone"),
                new BlockSpec(0, 1, 0, "minecraft:stone"));
        assertEquals(List.of("0,0,0", "0,1,0", "0,2,0"), states(BuildOrder.order(bp, s -> false, 0, 0)));
    }

    @Test
    void airFirstInLayer() {
        Blueprint bp = blueprint(
                new BlockSpec(0, 1, 0, "minecraft:stone"),
                new BlockSpec(1, 1, 0, "minecraft:air"),
                new BlockSpec(2, 1, 0, "minecraft:stone"));
        List<BlockSpec> ordered = BuildOrder.order(bp, s -> false, 0, 0);
        assertEquals("minecraft:air", ordered.get(0).state());
        assertEquals(3, ordered.size());
    }

    @Test
    void supportsBeforeAttachments() {
        Blueprint bp = blueprint(
                new BlockSpec(0, 1, 0, "minecraft:wall_torch[facing=south]"),
                new BlockSpec(0, 1, 1, "minecraft:stone"));
        List<BlockSpec> ordered = BuildOrder.order(bp, BuildOrderTest::needsSupport, 0, 0);
        assertEquals("minecraft:stone", ordered.get(0).state());
        assertEquals("minecraft:wall_torch[facing=south]", ordered.get(1).state());
    }

    @Test
    void serpentineFromNearestSide() {
        Blueprint bp = blueprint(
                new BlockSpec(0, 0, 0, "minecraft:stone"),
                new BlockSpec(1, 0, 0, "minecraft:stone"),
                new BlockSpec(2, 0, 0, "minecraft:stone"),
                new BlockSpec(0, 0, 1, "minecraft:stone"),
                new BlockSpec(1, 0, 1, "minecraft:stone"),
                new BlockSpec(2, 0, 1, "minecraft:stone"),
                new BlockSpec(0, 0, 2, "minecraft:stone"),
                new BlockSpec(1, 0, 2, "minecraft:stone"),
                new BlockSpec(2, 0, 2, "minecraft:stone"));
        List<String> fromWest = states(BuildOrder.order(bp, s -> false, 0, 0));
        assertEquals(List.of(
                "0,0,0", "1,0,0", "2,0,0",
                "2,0,1", "1,0,1", "0,0,1",
                "0,0,2", "1,0,2", "2,0,2"), fromWest);
        List<String> fromEast = states(BuildOrder.order(bp, s -> false, 2, 2));
        assertEquals(List.of(
                "2,0,2", "1,0,2", "0,0,2",
                "0,0,1", "1,0,1", "2,0,1",
                "2,0,0", "1,0,0", "0,0,0"), fromEast);
    }

    @Test
    void doorUpperHalfDropped() {
        Blueprint bp = blueprint(
                new BlockSpec(0, 1, 0, "minecraft:oak_door[facing=south,half=lower]"),
                new BlockSpec(0, 2, 0, "minecraft:oak_door[facing=south,half=upper]"));
        List<BlockSpec> ordered = BuildOrder.order(bp, BuildOrderTest::needsSupport, 0, 0);
        assertEquals(1, ordered.size());
        assertEquals(1, ordered.get(0).y());
    }

    @Test
    void bedHeadDropped() {
        Blueprint bp = blueprint(
                new BlockSpec(0, 1, 0, "minecraft:red_bed[facing=north,part=foot]"),
                new BlockSpec(0, 1, 1, "minecraft:red_bed[facing=north,part=head]"));
        List<BlockSpec> ordered = BuildOrder.order(bp, s -> false, 0, 0);
        assertEquals(1, ordered.size());
        assertFalse(ordered.get(0).state().contains("part=head"));
    }
}
