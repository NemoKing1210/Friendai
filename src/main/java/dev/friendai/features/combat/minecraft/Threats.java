package dev.friendai.features.combat.minecraft;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.combat.ThreatClass;
import dev.friendai.features.combat.ThreatRules;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
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

/** Who is a threat, who is only a danger, and who must never be attacked. */
public final class Threats {
    private static final int PROVOCATION_TICKS = 200;
    private static final double KIN_RADIUS = 8.0;

    private Threats() {}

    /** One mob's classification and the permissions that follow from it. */
    public record Assessment(
            ThreatClass type, boolean attackable, boolean danger, boolean protectedEntity, String reason) {}

    /** Mobs the companion may attack now: hostile, or provoked neutral mobs, minus protected ones. */
    public static List<LivingEntity> threatsNear(
            ServerLevel level, Vec3 center, double radius, CompanionEntity companion) {
        return nearby(level, center, radius).stream()
                .filter(entity -> assess(entity, companion, false).attackable())
                .toList();
    }

    /** Mobs to avoid or retreat from: attackable threats plus warden, dragon and a provoked group. */
    public static List<LivingEntity> dangersNear(
            ServerLevel level, Vec3 center, double radius, CompanionEntity companion) {
        return nearby(level, center, radius).stream()
                .filter(entity -> assess(entity, companion, false).danger())
                .toList();
    }

    public static boolean isAttackable(LivingEntity entity, CompanionEntity companion) {
        return assess(entity, companion, false).attackable();
    }

    public static boolean isDanger(LivingEntity entity, CompanionEntity companion) {
        return assess(entity, companion, false).danger();
    }

    public static Assessment assess(LivingEntity entity, CompanionEntity companion, boolean ownerOrder) {
        ServerLevel level = (ServerLevel) companion.level();
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        Optional<ServerPlayer> owner = companion.owner();
        LivingEntity target = entity instanceof net.minecraft.world.entity.Mob mob ? mob.getTarget() : null;
        boolean targetingUs = target == companion || (owner.isPresent() && target == owner.get());
        boolean hitUsRecently = recentlyHit(companion, entity)
                || owner.map(player -> recentlyHit(player, entity)).orElse(false);
        boolean daylight = !level.isDarkOutside();
        boolean groupRisk = ThreatRules.GROUP_RISK.contains(id) && hasKinNear(level, entity);
        ThreatRules.Decision decision = ThreatRules.decide(
                id, entity instanceof Enemy, targetingUs, hitUsRecently, daylight, ownerOrder, groupRisk);
        boolean protectedEntity = isProtected(entity, companion, ownerOrder);
        return new Assessment(
                decision.type(),
                decision.attackable() && !protectedEntity,
                decision.danger() && !protectedEntity,
                protectedEntity,
                decision.reason());
    }

    private static List<LivingEntity> nearby(ServerLevel level, Vec3 center, double radius) {
        return level
                .getEntitiesOfClass(
                        LivingEntity.class, new AABB(center, center).inflate(radius), e -> possibleThreat(e))
                .stream()
                .sorted(Comparator.comparingDouble(e -> e.position().distanceToSqr(center)))
                .toList();
    }

    /** Cheap prefilter: only an enemy or a mob the table never attacks can become a threat or a danger. */
    private static boolean possibleThreat(LivingEntity entity) {
        if (!entity.isAlive() || entity.isRemoved() || entity.isInvulnerable()) {
            return false;
        }
        if (entity instanceof Enemy) {
            return true;
        }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        return ThreatRules.AVOID.contains(id);
    }

    private static boolean recentlyHit(LivingEntity victim, LivingEntity attacker) {
        return victim.getLastHurtByMob() == attacker
                && victim.tickCount - victim.getLastHurtByMobTimestamp() <= PROVOCATION_TICKS;
    }

    private static boolean hasKinNear(ServerLevel level, LivingEntity entity) {
        return !level.getEntitiesOfClass(
                        LivingEntity.class,
                        new AABB(entity.position(), entity.position()).inflate(KIN_RADIUS),
                        other -> other != entity
                                && other.isAlive()
                                && !other.isRemoved()
                                && other.getType() == entity.getType())
                .isEmpty();
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
