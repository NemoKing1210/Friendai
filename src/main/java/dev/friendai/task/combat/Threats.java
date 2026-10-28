package dev.friendai.task.combat;

import dev.friendai.entity.CompanionEntity;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Who is a threat, and who must never be attacked. */
public final class Threats {
    private Threats() {}

    /** Hostile mobs near a point, nearest first. Line of sight is the caller's concern. */
    public static List<LivingEntity> hostilesNear(ServerLevel level, Vec3 center, double radius) {
        return level
                .getEntitiesOfClass(
                        LivingEntity.class,
                        new AABB(center, center).inflate(radius),
                        e -> e.isAlive() && !e.isRemoved() && e instanceof Enemy && !e.isInvulnerable())
                .stream()
                .sorted(Comparator.comparingDouble(e -> e.position().distanceToSqr(center)))
                .toList();
    }

    /**
     * Entities the companion never attacks on its own: the owner, other players, other companions, someone else's
     * pets and named mobs, villagers, golems. Fighting back is decided elsewhere.
     */
    public static boolean isProtected(LivingEntity entity, CompanionEntity companion) {
        return isProtected(entity, companion, false);
    }

    /**
     * The same rule with the owner's explicit order taken into account. {@code ownerOrder} lifts only the species
     * rule (villagers, wandering traders and golems), because the owner asking for it is the permission; it never
     * lifts the rest — another player, their pets and their named mobs stay off limits.
     */
    public static boolean isProtected(LivingEntity entity, CompanionEntity companion, boolean ownerOrder) {
        if (entity == companion || entity instanceof CompanionEntity) {
            return true;
        }
        Optional<ServerPlayer> owner = companion.owner();
        if (owner.isPresent() && entity == owner.get()) {
            return true;
        }
        if (entity instanceof Player) {
            return true;
        }
        if (!ownerOrder
                && (entity instanceof Villager
                        || entity instanceof WanderingTrader
                        || entity instanceof IronGolem
                        || entity instanceof SnowGolem)) {
            return true;
        }
        LivingEntity entityOwner = entity instanceof OwnableEntity ownable ? ownable.getOwner() : null;
        boolean ownedByUs = entityOwner != null && owner.isPresent() && entityOwner == owner.get();
        if (entityOwner != null && !ownedByUs) {
            return true;
        }
        return entity.hasCustomName() && !ownedByUs;
    }

    /** A player may be fought only when the owner is allowed to hurt them here. */
    public static boolean mayFightPlayer(CompanionEntity companion, ServerPlayer target) {
        return companion
                .owner()
                .map(owner -> owner != target && owner.canHarmPlayer(target))
                .orElse(false);
    }
}
