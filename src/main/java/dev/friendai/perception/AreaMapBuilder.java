package dev.friendai.perception;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.knowledge.ResourceObservation;
import dev.friendai.perception.resource.ResourceCandidates;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/** Draws the area around the companion at its eye level. Server thread only. */
public final class AreaMapBuilder {
    private AreaMapBuilder() {}

    /** The map around {@code c}; the speaker is {@code O} when the principal (or owner) is in sight. */
    public static String of(CompanionEntity c, UUID principal, int radius) {
        return of(c, principal, radius, null);
    }

    public static String of(CompanionEntity c, UUID principal, int radius, ResourceCandidates candidates) {
        BlockPos center = c.blockPosition();
        BlockPos speaker = OwnerFocusBuilder.speakerBlock(c, principal).orElse(null);
        Map<Long, String> observed = new HashMap<>();
        if (candidates != null) {
            for (ResourceObservation observation : candidates.confirmedRecent(c, radius + 1)) {
                var pos = observation.location().pos();
                observed.put(new BlockPos(pos.x(), pos.y(), pos.z()).asLong(), observation.blockId());
            }
        }
        return AreaMap.render(radius, (dx, dz) -> {
            BlockPos pos = center.offset(dx, 0, dz);
            if (pos.equals(center)) {
                return '@';
            }
            if (pos.equals(speaker)) {
                return 'O';
            }
            String blockId = observed.get(pos.asLong());
            if (blockId == null) return '?';
            if (blockId.equals("minecraft:water")) {
                return '~';
            }
            if (blockId.equals("minecraft:lava")
                    || blockId.equals("minecraft:fire")
                    || blockId.equals("minecraft:soul_fire")) {
                return '^';
            }
            return '#';
        });
    }
}
