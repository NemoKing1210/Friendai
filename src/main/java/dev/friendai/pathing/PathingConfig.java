package dev.friendai.pathing;

import java.util.List;

/**
 * The server's pathing settings, copied out of the config so the pure pathing code never touches JSON.
 *
 * @param breakBlacklist block ids and {@code #tag}s the companion never digs through
 * @param placeBlacklist extra entries on top of the break blacklist that are never used as material
 */
public record PathingConfig(
        boolean allowBreak,
        boolean allowPlace,
        List<String> breakBlacklist,
        List<String> placeBlacklist,
        int searchBudgetMs,
        int maxNodes,
        int maxGatherBlocks) {

    public static final PathingConfig DEFAULTS = new PathingConfig(
            true,
            true,
            List.of(
                    "#minecraft:beds",
                    "#minecraft:doors",
                    "#minecraft:trapdoors",
                    "#minecraft:fence_gates",
                    "#minecraft:all_signs",
                    "#minecraft:shulker_boxes",
                    "#minecraft:planks",
                    "#minecraft:wooden_stairs",
                    "#minecraft:wooden_slabs",
                    "#minecraft:fences",
                    "#minecraft:walls",
                    "#minecraft:wool",
                    "#minecraft:wool_carpets",
                    "#c:concretes",
                    "#c:ores",
                    "#c:storage_blocks",
                    "#c:glass_blocks",
                    "#c:glass_panes",
                    "#c:chests",
                    "#c:barrels",
                    "minecraft:furnace",
                    "minecraft:blast_furnace",
                    "minecraft:smoker",
                    "minecraft:hopper",
                    "minecraft:respawn_anchor",
                    "minecraft:beacon",
                    "minecraft:obsidian",
                    "minecraft:crying_obsidian",
                    "minecraft:bricks",
                    "minecraft:item_frame",
                    "minecraft:nether_portal",
                    "minecraft:end_portal_frame"),
            List.of(),
            2,
            20_000,
            64);

    public PathingConfig {
        breakBlacklist = List.copyOf(breakBlacklist);
        placeBlacklist = List.copyOf(placeBlacklist);
    }
}
