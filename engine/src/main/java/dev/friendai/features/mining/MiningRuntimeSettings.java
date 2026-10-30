package dev.friendai.features.mining;

import dev.friendai.features.navigation.pathing.BlockList;
import java.util.List;
import java.util.Set;

/**
 * Mining policy copied from the server config: whether a valuable drop is protected before the block is broken, which
 * blocks count as valuable, and how many blocks one drop may cost. Immutable, so a task reads it without touching JSON.
 */
public record MiningRuntimeSettings(boolean protectDrops, BlockList valuable, int catchBlocks) {

    /** Ore-bearing blocks and the materials worth spending a platform on; stone, dirt and logs never are. */
    public static final List<String> DEFAULT_VALUABLE =
            List.of("#c:ores", "minecraft:ancient_debris", "minecraft:amethyst_cluster", "minecraft:amethyst_block");

    public static final int DEFAULT_CATCH_BLOCKS = 9;

    public static final MiningRuntimeSettings DEFAULTS =
            new MiningRuntimeSettings(true, BlockList.parse(DEFAULT_VALUABLE, ignored -> {}), DEFAULT_CATCH_BLOCKS);

    public MiningRuntimeSettings {
        valuable = valuable == null ? BlockList.parse(DEFAULT_VALUABLE, ignored -> {}) : valuable;
        catchBlocks = Math.clamp(catchBlocks, 0, 27);
    }

    /** Whether the drop of this block is worth a platform. */
    public boolean protects(String blockId, Set<String> blockTags) {
        return protectDrops && valuable.matches(blockId, blockTags);
    }
}
