package dev.friendai.foundation.spatial;

import java.util.ArrayList;
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

    /**
     * How far from the start a search may look, in blocks along each horizontal axis. Shared with the callers that
     * size an expedition leg and with {@link SearchOptions#DEFAULTS}, so a search and the walk that feeds it agree.
     */
    public static final int SEARCH_RANGE = 64;

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

    /** A copy with digging and placing switched; every limit and blacklist is kept. */
    public PathingConfig withActions(boolean allowBreak, boolean allowPlace) {
        return new PathingConfig(
                allowBreak, allowPlace, breakBlacklist, placeBlacklist, searchBudgetMs, maxNodes, maxGatherBlocks);
    }

    /**
     * A copy that also refuses to dig the named blocks. A caller whose own work owns those blocks, such as a feller
     * whose route must never cut the trunk it is felling, adds them here instead of walking through them.
     */
    public PathingConfig withAdditionalBreaks(List<String> blocks) {
        List<String> extended = new ArrayList<>(breakBlacklist);
        for (String block : blocks) {
            if (!extended.contains(block)) {
                extended.add(block);
            }
        }
        return new PathingConfig(
                allowBreak, allowPlace, extended, placeBlacklist, searchBudgetMs, maxNodes, maxGatherBlocks);
    }
}
