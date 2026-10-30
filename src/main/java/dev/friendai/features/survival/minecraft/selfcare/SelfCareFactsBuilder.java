package dev.friendai.features.survival.minecraft.selfcare;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.combat.minecraft.Threats;
import dev.friendai.features.survival.selfcare.SelfCareFacts;
import dev.friendai.features.survival.selfcare.SelfCarePlanner;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;

/** Reads one tick of the companion's body into the pure facts. Cheap enough to run every tick: no query is larger
 * than the inventory or the few blocks below a falling body. */
final class SelfCareFactsBuilder {
    private static final double OWNER_THREAT_RADIUS = 3.0;

    private final CompanionEntity companion;
    private final ServerLevel level;

    SelfCareFactsBuilder(CompanionEntity companion) {
        this.companion = companion;
        this.level = (ServerLevel) companion.level();
    }

    SelfCareFacts build(Optional<BlockPos> support) {
        double supportDistance =
                support.map(pos -> companion.position().y - (pos.getY() + 1)).orElse(-1.0);
        // A mock or disconnected owner is already removed, so liveness is the health, not isAlive().
        ServerPlayer owner = companion
                .owner()
                .filter(player -> player.getHealth() > 0.0F && player.level() == level)
                .orElse(null);
        float ownerHealth = owner == null ? 1.0F : owner.getHealth() / Math.max(1.0F, owner.getMaxHealth());
        boolean ownerThreatNear = owner != null
                && ownerHealth <= SelfCarePlanner.OWNER_HURT
                && !Threats.hostilesNear(level, owner.position(), OWNER_THREAT_RADIUS)
                        .isEmpty();
        return new SelfCareFacts(
                companion.getHealth() / Math.max(1.0F, companion.getMaxHealth()),
                companion.onGround(),
                companion.isInWater(),
                companion.isEyeInFluid(FluidTags.WATER),
                companion.isInLava(),
                companion.isOnFire(),
                (float) companion.fallDistance,
                supportDistance,
                support.isPresent(),
                companion.getAirSupply(),
                companion.taskExecution().current().isPresent(),
                owner != null,
                owner == null ? Double.MAX_VALUE : companion.distanceTo(owner),
                ownerHealth,
                ownerThreatNear,
                SelfCareItems.effects(companion),
                SelfCareItems.stock(companion.inventory()));
    }
}
