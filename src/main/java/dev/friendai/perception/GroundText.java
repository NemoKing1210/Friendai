package dev.friendai.perception;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

/** What a mob stands on, as LLM-facing English. Server thread only. */
final class GroundText {
    /** How far below its feet a mob is checked when it is not on the ground. */
    private static final int SCAN = 16;

    private GroundText() {}

    static String under(ServerLevel level, Entity entity) {
        if (entity.onGround()) {
            BlockPos under = entity.getOnPos();
            return blockName(level, under) + " at " + coords(under);
        }
        BlockPos feet = entity.blockPosition();
        for (int n = 1; n <= SCAN; n++) {
            BlockPos pos = feet.below(n);
            if (!level.hasChunkAt(pos)) {
                break;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty()) {
                return "in the air above " + blockName(level, pos) + " at " + coords(pos) + " (" + n
                        + (n == 1 ? " block" : " blocks") + " below)";
            }
        }
        return "in the air (nothing within " + SCAN + " blocks below)";
    }

    private static String blockName(ServerLevel level, BlockPos pos) {
        return shortName(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String shortName(Identifier id) {
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }
}
