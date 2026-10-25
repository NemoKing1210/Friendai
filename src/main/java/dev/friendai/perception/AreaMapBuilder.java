package dev.friendai.perception;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.combat.Threats;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Draws the area around the companion at its eye level. Server thread only. */
public final class AreaMapBuilder {
    private AreaMapBuilder() {}

    /** The map around {@code c}; the speaker is {@code O} when the principal (or owner) is in sight. */
    public static String of(CompanionEntity c, UUID principal, int radius) {
        ServerLevel level = (ServerLevel) c.level();
        BlockPos center = c.blockPosition();
        BlockPos speaker = OwnerFocusBuilder.speakerBlock(c, principal).orElse(null);
        Set<Long> hostiles = new HashSet<>();
        for (var hostile : Threats.hostilesNear(level, c.position(), radius + 2)) {
            hostiles.add(hostile.blockPosition().asLong());
        }
        return AreaMap.render(radius, (dx, dz) -> {
            BlockPos pos = center.offset(dx, 0, dz);
            if (pos.equals(center)) {
                return '@';
            }
            if (!level.hasChunkAt(pos)) {
                return '?';
            }
            if (pos.equals(speaker)) {
                return 'O';
            }
            if (hostiles.contains(pos.asLong())) {
                return 'Z';
            }
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.WATER)) {
                return '~';
            }
            if (state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) {
                return '^';
            }
            return state.getCollisionShape(level, pos).isEmpty() ? '.' : '#';
        });
    }
}
