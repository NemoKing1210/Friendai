package dev.friendai.features.construction.blueprint;

import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Maps a block the model named for a roof to the stairs block the roof actually needs. The mapping is data plus a
 * conservative fallback: every candidate is checked against the caller's known-state predicate, so a name is never
 * assumed to exist and a block without a stairs variant stays unsupported.
 */
public final class RoofStairs {
    private static final String NS = "minecraft:";

    /** Vanilla names whose stairs form is not a simple suffix transformation. */
    private static final Map<String, String> IRREGULAR = Map.ofEntries(
            Map.entry(NS + "bricks", NS + "brick_stairs"),
            Map.entry(NS + "stone_bricks", NS + "stone_brick_stairs"),
            Map.entry(NS + "mossy_stone_bricks", NS + "mossy_stone_brick_stairs"),
            Map.entry(NS + "deepslate_bricks", NS + "deepslate_brick_stairs"),
            Map.entry(NS + "deepslate_tiles", NS + "deepslate_tile_stairs"),
            Map.entry(NS + "mud_bricks", NS + "mud_brick_stairs"),
            Map.entry(NS + "prismarine_bricks", NS + "prismarine_brick_stairs"),
            Map.entry(NS + "end_stone_bricks", NS + "end_stone_brick_stairs"),
            Map.entry(NS + "nether_bricks", NS + "nether_brick_stairs"),
            Map.entry(NS + "red_nether_bricks", NS + "red_nether_brick_stairs"),
            Map.entry(NS + "polished_blackstone_bricks", NS + "polished_blackstone_brick_stairs"),
            Map.entry(NS + "quartz_block", NS + "quartz_stairs"),
            Map.entry(NS + "purpur_block", NS + "purpur_stairs"));

    private RoofStairs() {}

    /**
     * @param block a block state id such as {@code minecraft:oak_planks} or {@code minecraft:oak_stairs[facing=east]}
     * @param knownState whether a block state id exists; the Minecraft layer implements it
     * @return the stairs id the roof may use, or empty when the block has no stairs variant
     */
    public static Optional<String> stairsFor(String block, Predicate<String> knownState) {
        String id = base(block);
        if (id.endsWith("_stairs")) {
            return candidate(id, knownState);
        }
        if (id.endsWith("_slab")) {
            return candidate(stem(id, "_slab") + "_stairs", knownState);
        }
        if (id.endsWith("_planks")) {
            return candidate(stem(id, "_planks") + "_stairs", knownState);
        }
        String irregular = IRREGULAR.get(id);
        if (irregular != null) {
            Optional<String> mapped = candidate(irregular, knownState);
            if (mapped.isPresent()) {
                return mapped;
            }
        }
        Optional<String> direct = candidate(id + "_stairs", knownState);
        if (direct.isPresent()) {
            return direct;
        }
        if (id.endsWith("_block")) {
            Optional<String> stripped = candidate(stem(id, "_block") + "_stairs", knownState);
            if (stripped.isPresent()) {
                return stripped;
            }
        }
        if (id.endsWith("s")) {
            return candidate(id.substring(0, id.length() - 1) + "_stairs", knownState);
        }
        return Optional.empty();
    }

    /** The id without a block-state suffix. */
    public static String base(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    private static Optional<String> candidate(String id, Predicate<String> knownState) {
        return knownState.test(id) ? Optional.of(id) : Optional.empty();
    }

    private static String stem(String id, String suffix) {
        return id.substring(0, id.length() - suffix.length());
    }
}
