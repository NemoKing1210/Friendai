package dev.friendai.features.mining.minecraft.mining;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Leaves and vines are cover: a feller may break them to reach a log, and nothing else. */
public final class SoftCover {
    private SoftCover() {}

    public static boolean isCover(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(Blocks.VINE);
    }
}
