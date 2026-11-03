package dev.friendai.server.agent.perception;

import dev.friendai.agent.perception.AreaMap;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.combat.minecraft.Threats;
import dev.friendai.features.navigation.minecraft.nav.Sight;
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
        BlockPos center = c.blockPosition().above();
        BlockPos speaker = OwnerFocusBuilder.speakerBlock(c, principal)
                .map(BlockPos::above)
                .orElse(null);
        ServerLevel level = (ServerLevel) c.level();
        Set<Long> dangers = new HashSet<>();
        for (var danger : Threats.dangersNear(level, c.position(), radius + 2, c)) {
            if (Sight.canSee(level, c, danger.blockPosition())) {
                dangers.add(BlockPos.containing(danger.getEyePosition()).asLong());
            }
        }
        StringBuilder map = new StringBuilder("map at ")
                .append(center.getX())
                .append(' ')
                .append(center.getY())
                .append(' ')
                .append(center.getZ())
                .append(", north up\n");
        map.append(AreaMap.render(radius, (dx, dz) -> {
            BlockPos pos = center.offset(dx, 0, dz);
            if (pos.equals(center)) {
                return '@';
            }
            if (pos.equals(speaker)) {
                return 'O';
            }
            if (!level.hasChunkAt(pos) || !Sight.canSee(level, c, pos)) {
                return '?';
            }
            if (dangers.contains(pos.asLong())) {
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
        }));
        return map.toString();
    }
}
