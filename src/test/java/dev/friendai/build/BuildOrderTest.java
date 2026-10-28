package dev.friendai.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class BuildOrderTest {
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

    /** No block knows what it needs, which is the orderer without any dependency knowledge. */
    private static final Function<BlockSpec, BuildOrder.Support> INDEPENDENT = spec -> BuildOrder.Support.NONE;

    @Test
    void layersBottomUp() {
        Blueprint bp = blueprint(
                new BlockSpec(0, 2, 0, "minecraft:stone"),
                new BlockSpec(0, 0, 0, "minecraft:stone"),
                new BlockSpec(0, 1, 0, "minecraft:stone"));
        assertEquals(List.of("0,0,0", "0,1,0", "0,2,0"), states(BuildOrder.order(bp, INDEPENDENT, 0, 0)));
    }

    @Test
    void airFirstInLayer() {
        Blueprint bp = blueprint(
                new BlockSpec(0, 1, 0, "minecraft:stone"),
                new BlockSpec(1, 1, 0, "minecraft:air"),
                new BlockSpec(2, 1, 0, "minecraft:stone"));
        List<BlockSpec> ordered = BuildOrder.order(bp, INDEPENDENT, 0, 0);
        assertEquals("minecraft:air", ordered.get(0).state());
        assertEquals(3, ordered.size());
    }

    @Test
    void attachmentsGoLastWhenTheirSupportIsNotInThePlan() {
        BlockSpec torch = new BlockSpec(0, 1, 0, "minecraft:wall_torch[facing=south]");
        Blueprint bp = blueprint(torch, new BlockSpec(1, 1, 0, "minecraft:stone"));
        List<BlockSpec> ordered = BuildOrder.order(
                bp, spec -> spec.equals(torch) ? BuildOrder.Support.needed() : BuildOrder.Support.NONE, 0, 0);
        assertEquals("minecraft:stone", ordered.get(0).state());
        assertEquals(torch, ordered.get(1));
    }

    @Test
    void aPlannedSupportIsPlacedBeforeTheBlockThatNeedsIt() {
        // The wall is later in the serpentine walk of the layer, so only the dependency can move the torch behind it.
        BlockSpec wall = new BlockSpec(2, 1, 0, "minecraft:stone");
        BlockSpec torch = new BlockSpec(1, 1, 0, "minecraft:wall_torch[facing=east]");
        Blueprint bp = blueprint(torch, wall);
        List<BlockSpec> ordered = BuildOrder.order(
                bp, spec -> spec.equals(torch) ? BuildOrder.Support.on(wall) : BuildOrder.Support.NONE, 0, 0);
        assertEquals(wall, ordered.get(0));
        assertEquals(torch, ordered.get(1));
    }

    @Test
    void aSupportInAHigherLayerIsPlacedFirst() {
        // A hanging lantern belongs to the layer under the beam it hangs from; the beam still has to be there first.
        BlockSpec beam = new BlockSpec(0, 2, 0, "minecraft:oak_planks");
        BlockSpec lantern = new BlockSpec(0, 1, 0, "minecraft:lantern[hanging=true]");
        Blueprint bp = blueprint(lantern, beam);
        List<BlockSpec> ordered = BuildOrder.order(
                bp, spec -> spec.equals(lantern) ? BuildOrder.Support.on(beam) : BuildOrder.Support.NONE, 0, 0);
        assertEquals(List.of("0,2,0", "0,1,0"), states(ordered));
    }

    @Test
    void anAirCellInThePlanIsNoSupport() {
        // The blueprint says the cell below must be empty: the block cannot wait for it, it needs a scaffold instead.
        BlockSpec air = new BlockSpec(0, 0, 0, "minecraft:air");
        BlockSpec torch = new BlockSpec(0, 1, 0, "minecraft:torch");
        Blueprint bp = blueprint(air, torch);
        List<BlockSpec> ordered = BuildOrder.order(
                bp, spec -> spec.equals(torch) ? BuildOrder.Support.needed() : BuildOrder.Support.NONE, 0, 0);
        assertEquals(2, ordered.size());
        assertEquals("minecraft:air", ordered.get(0).state());
    }

    @Test
    void aCycleStillEmitsEveryBlockOnce() {
        BlockSpec a = new BlockSpec(0, 1, 0, "minecraft:stone");
        BlockSpec b = new BlockSpec(1, 1, 0, "minecraft:stone");
        Blueprint bp = blueprint(a, b);
        Map<BlockSpec, BlockSpec> deps = Map.of(a, b, b, a);
        List<BlockSpec> ordered = BuildOrder.order(bp, spec -> BuildOrder.Support.on(deps.get(spec)), 0, 0);
        assertEquals(2, ordered.size());
        assertTrue(ordered.contains(a) && ordered.contains(b));
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
        List<String> fromWest = states(BuildOrder.order(bp, INDEPENDENT, 0, 0));
        assertEquals(
                List.of("0,0,0", "1,0,0", "2,0,0", "2,0,1", "1,0,1", "0,0,1", "0,0,2", "1,0,2", "2,0,2"), fromWest);
        List<String> fromEast = states(BuildOrder.order(bp, INDEPENDENT, 2, 2));
        assertEquals(
                List.of("2,0,2", "1,0,2", "0,0,2", "0,0,1", "1,0,1", "2,0,1", "2,0,0", "1,0,0", "0,0,0"), fromEast);
    }

    @Test
    void doorUpperHalfDropped() {
        Blueprint bp = blueprint(
                new BlockSpec(0, 1, 0, "minecraft:oak_door[facing=south,half=lower]"),
                new BlockSpec(0, 2, 0, "minecraft:oak_door[facing=south,half=upper]"));
        List<BlockSpec> ordered = BuildOrder.order(bp, INDEPENDENT, 0, 0);
        assertEquals(1, ordered.size());
        assertEquals(1, ordered.get(0).y());
    }

    @Test
    void bedHeadDropped() {
        Blueprint bp = blueprint(
                new BlockSpec(0, 1, 0, "minecraft:red_bed[facing=north,part=foot]"),
                new BlockSpec(0, 1, 1, "minecraft:red_bed[facing=north,part=head]"));
        List<BlockSpec> ordered = BuildOrder.order(bp, INDEPENDENT, 0, 0);
        assertEquals(1, ordered.size());
        assertFalse(ordered.get(0).state().contains("part=head"));
    }
}
