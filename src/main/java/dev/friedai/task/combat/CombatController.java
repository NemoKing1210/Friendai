package dev.friedai.task.combat;

import dev.friedai.entity.CompanionEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Reflex combat, ticked before the task runner: fight back, defend the owner, guard. */
public final class CombatController {
    public enum State {
        IDLE,
        FIGHTING,
        RETREATING,
        EATING
    }

    private static final int SCAN_INTERVAL = 10;
    private static final int ATTACKER_MEMORY = 100;
    private static final double OWNER_DEFENSE_RANGE = 16.0;
    private static final double TARGETING_RANGE = 8.0;
    private static final double GUARD_RADIUS = 12.0;
    private static final double FIGHT_DROPOFF = 24.0;
    private static final int OUT_OF_SIGHT_TICKS = 100;
    private static final double DROP_COLLECT_RADIUS = 4.0;
    private static final double RANGED_DISTANCE = 6.0;
    private static final int BOW_DRAW_TICKS = 20;
    private static final float ARROW_SPEED = 3.0F;
    private static final float ARROW_INACCURACY = 2.0F;
    private static final double CREEPER_FLEE_RADIUS = 5.0;
    private static final double LOW_HEALTH_FRACTION = 0.25;
    private static final double RETREAT_THREAT_RADIUS = 8.0;
    private static final double RETREAT_SAFE_RADIUS = 12.0;
    private static final int RETREAT_MAX_TICKS = 200;
    private static final double FLEE_DISTANCE = 8.0;

    private enum Kind {
        SELF,
        OWNER,
        GUARD
    }

    private record Selection(LivingEntity entity, Kind kind) {}

    private final CompanionEntity companion;
    private final Map<String, Integer> kills = new LinkedHashMap<>();

    private State state = State.IDLE;
    private LivingEntity target;
    private int nextScan;
    private int attackCooldown;
    private int repathTicks;
    private int lastSeenTick;
    private boolean taskDriven;
    private boolean weaponSwapped;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private boolean usingBow;
    private int drawTicks;
    private int unreachableTicks;
    private int retreatTicks;
    private int retreatRepathTicks;
    private boolean retreatNoticed;
    private boolean retreatFromCreeper;
    private Vec3 retreatTarget;

    public CombatController(CompanionEntity companion) {
        this.companion = companion;
    }

    public State state() {
        return state;
    }

    public Optional<LivingEntity> target() {
        return Optional.ofNullable(target);
    }

    /** True once a fight the tasks asked for has ended; also true while no such fight is running. */
    public boolean fightOver() {
        return state == State.IDLE;
    }

    /** Starts a fight on a task's behalf: the runner is not paused and the brain gets no notices. */
    public void fight(LivingEntity enemy) {
        if (state != State.IDLE || enemy == null || !enemy.isAlive() || !(companion.level() instanceof ServerLevel level)) {
            return;
        }
        beginFight(level, new Selection(enemy, Kind.SELF), true);
    }

    public void tick() {
        if (!(companion.level() instanceof ServerLevel level) || !companion.isAlive()) {
            return;
        }
        int now = companion.tickCount;
        Creeper creeper = swellingCreeper(level);
        if (creeper != null) {
            if (state != State.RETREATING || !retreatFromCreeper) {
                beginRetreat(level, List.of(creeper), true);
            }
            tickRetreat(level, now, List.of(creeper));
            return;
        }
        if (state == State.RETREATING) {
            tickRetreat(level, now, threatsNear(level).stream()
                    .filter(threat -> companion.distanceTo(threat) <= RETREAT_SAFE_RADIUS)
                    .toList());
            return;
        }
        List<LivingEntity> threats = threatsNear(level).stream()
                .filter(threat -> companion.distanceTo(threat) <= RETREAT_SAFE_RADIUS)
                .toList();
        if (companion.getHealth() <= companion.getMaxHealth() * LOW_HEALTH_FRACTION && !threats.isEmpty()) {
            beginRetreat(level, threats, false);
            tickRetreat(level, now, threats);
            return;
        }
        if (state == State.FIGHTING) {
            tickFight(level, now);
            return;
        }
        if (state != State.IDLE) {
            return;
        }
        if (companion.combatMode() == CombatMode.PASSIVE) {
            List<LivingEntity> passiveThreats = ruleOneThreats(level).stream()
                    .filter(threat -> companion.distanceTo(threat) <= RETREAT_SAFE_RADIUS)
                    .toList();
            if (!passiveThreats.isEmpty()) {
                beginRetreat(level, passiveThreats, false);
                tickRetreat(level, now, passiveThreats);
            }
            return;
        }
        if (now < nextScan) {
            return;
        }
        nextScan = now + SCAN_INTERVAL;
        Selection selection = selectTarget(level);
        if (selection != null) {
            beginFight(level, selection, false);
        }
    }

    private Creeper swellingCreeper(ServerLevel level) {
        for (Creeper creeper : level.getEntitiesOfClass(
                Creeper.class, new AABB(companion.position(), companion.position()).inflate(CREEPER_FLEE_RADIUS))) {
            if (creeper.isAlive() && !creeper.isRemoved() && creeper.getSwellDir() > 0) {
                return creeper;
            }
        }
        return null;
    }

    /** Rule 1: what attacked the companion or the owner, or is targeting either of them nearby. */
    private List<LivingEntity> ruleOneThreats(ServerLevel level) {
        Set<LivingEntity> threats = new LinkedHashSet<>();
        LivingEntity attacker = companion.getLastHurtByMob();
        if (validAttacker(attacker) && companion.tickCount - companion.getLastHurtByMobTimestamp() <= ATTACKER_MEMORY) {
            threats.add(attacker);
        }
        ServerPlayer owner = companion.owner().filter(o -> o.level() == level).orElse(null);
        if (owner != null && companion.distanceTo(owner) <= OWNER_DEFENSE_RANGE) {
            LivingEntity ownerAttacker = owner.getLastHurtByMob();
            if (validAttacker(ownerAttacker)
                    && owner.tickCount - owner.getLastHurtByMobTimestamp() <= ATTACKER_MEMORY) {
                threats.add(ownerAttacker);
            }
        }
        for (LivingEntity entity : level.getEntitiesOfClass(
                LivingEntity.class, new AABB(companion.position(), companion.position()).inflate(TARGETING_RANGE))) {
            if (!validAttacker(entity) || !(entity instanceof Mob mob)) {
                continue;
            }
            if (mob.getTarget() == companion || (owner != null && mob.getTarget() == owner)) {
                threats.add(entity);
            }
        }
        return List.copyOf(threats);
    }

    /** Rule 1 plus any hostile mob within reach; used by the low-health retreat. */
    private List<LivingEntity> threatsNear(ServerLevel level) {
        List<LivingEntity> threats = new ArrayList<>(ruleOneThreats(level));
        for (LivingEntity hostile : Threats.hostilesNear(level, companion.position(), RETREAT_THREAT_RADIUS)) {
            if (!Threats.isProtected(hostile, companion) && !threats.contains(hostile)) {
                threats.add(hostile);
            }
        }
        return threats;
    }

    private void beginRetreat(ServerLevel level, List<LivingEntity> threats, boolean fromCreeper) {
        if (state == State.FIGHTING) {
            abandonFight();
        }
        state = State.RETREATING;
        retreatTicks = 0;
        retreatRepathTicks = 0;
        retreatNoticed = false;
        retreatFromCreeper = fromCreeper;
        retreatTarget = null;
        companion.taskRunner().setPaused(true);
        retreatNoticed();
        if (!threats.isEmpty() && !fromCreeper) {
            retreatTarget = chooseRetreatTarget(level, threats);
        }
    }

    private void tickRetreat(ServerLevel level, int now, List<LivingEntity> threats) {
        retreatTicks++;
        if (retreatTicks > RETREAT_MAX_TICKS) {
            finishRetreat();
            return;
        }
        if (retreatFromCreeper) {
            if (swellingCreeper(level) == null) {
                finishRetreat();
                return;
            }
        } else if (threats.isEmpty()) {
            finishRetreat();
            return;
        }
        if (--retreatRepathTicks <= 0 || retreatTarget == null) {
            retreatTarget = chooseRetreatTarget(level, threats);
            retreatRepathTicks = 10;
        }
        if (retreatTarget != null) {
            companion.getNavigation().moveTo(retreatTarget.x, retreatTarget.y, retreatTarget.z, 1.2);
        }
    }

    private Vec3 chooseRetreatTarget(ServerLevel level, List<LivingEntity> threats) {
        if (threats.isEmpty()) {
            return null;
        }
        Vec3 centroid = Vec3.ZERO;
        for (LivingEntity threat : threats) {
            centroid = centroid.add(threat.position());
        }
        centroid = centroid.scale(1.0 / threats.size());
        ServerPlayer owner = companion.owner().filter(o -> o.level() == level).orElse(null);
        if (owner != null
                && owner.position().distanceToSqr(centroid) > companion.position().distanceToSqr(centroid)) {
            return owner.position();
        }
        Vec3 away = DefaultRandomPos.getPosAway(companion, (int) FLEE_DISTANCE, (int) FLEE_DISTANCE, centroid);
        if (away != null) {
            return away;
        }
        Vec3 direction = companion.position().subtract(centroid);
        if (direction.lengthSqr() < 1.0E-4) {
            direction = new Vec3(1.0, 0.0, 0.0);
        }
        return companion.position().add(direction.normalize().scale(FLEE_DISTANCE));
    }

    private void retreatNoticed() {
        if (retreatNoticed) {
            return;
        }
        retreatNoticed = true;
        String text = "retreating: health " + number(companion.getHealth()) + "/" + number(companion.getMaxHealth());
        companion.taskRunner().notice(text + ", I have no food");
    }

    private void finishRetreat() {
        state = State.IDLE;
        retreatTarget = null;
        retreatFromCreeper = false;
        companion.getNavigation().stop();
        companion.taskRunner().setPaused(false);
    }

    private Selection selectTarget(ServerLevel level) {
        LivingEntity attacker = companion.getLastHurtByMob();
        if (validAttacker(attacker) && companion.tickCount - companion.getLastHurtByMobTimestamp() <= ATTACKER_MEMORY) {
            return new Selection(attacker, Kind.SELF);
        }

        ServerPlayer owner = companion.owner().filter(o -> o.level() == level).orElse(null);
        if (owner != null && companion.distanceTo(owner) <= OWNER_DEFENSE_RANGE) {
            LivingEntity ownerAttacker = owner.getLastHurtByMob();
            if (validAttacker(ownerAttacker)
                    && owner.tickCount - owner.getLastHurtByMobTimestamp() <= ATTACKER_MEMORY) {
                return new Selection(ownerAttacker, Kind.OWNER);
            }
        }

        List<LivingEntity> around = level.getEntitiesOfClass(
                LivingEntity.class, new AABB(companion.position(), companion.position()).inflate(TARGETING_RANGE));
        for (LivingEntity entity : around) {
            if (!validAttacker(entity) || !(entity instanceof Mob mob)) {
                continue;
            }
            if (mob.getTarget() == companion) {
                return new Selection(entity, Kind.SELF);
            }
            if (owner != null && mob.getTarget() == owner) {
                return new Selection(entity, Kind.OWNER);
            }
        }

        if (companion.combatMode() == CombatMode.GUARD) {
            Vec3 center = owner != null ? owner.position() : companion.position();
            for (LivingEntity entity : Threats.hostilesNear(level, center, GUARD_RADIUS)) {
                if (!Threats.isProtected(entity, companion) && companion.hasLineOfSight(entity)) {
                    return new Selection(entity, Kind.GUARD);
                }
            }
        }
        return null;
    }

    private boolean validAttacker(LivingEntity entity) {
        if (entity == null || entity == companion || entity.isRemoved() || !entity.isAlive()) {
            return false;
        }
        if (entity instanceof CompanionEntity) {
            return false;
        }
        if (companion.owner().map(owner -> owner == entity).orElse(false)) {
            return false;
        }
        if (entity instanceof ServerPlayer player) {
            return Threats.mayFightPlayer(companion, player);
        }
        if (entity instanceof Player) {
            return false;
        }
        return !entity.isInvulnerable();
    }

    private void beginFight(ServerLevel level, Selection selection, boolean ordered) {
        state = State.FIGHTING;
        target = selection.entity();
        taskDriven = ordered;
        attackCooldown = 0;
        repathTicks = 0;
        unreachableTicks = 0;
        drawTicks = 0;
        usingBow = false;
        lastSeenTick = companion.tickCount;
        kills.clear();
        companion.stopUsingItem();
        equipWeapon();
        if (!ordered) {
            companion.taskRunner().setPaused(true);
            companion.taskRunner().notice(startNotice(selection));
        }
    }

    private void equipWeapon() {
        weaponSwapped = false;
        previousMainHand = ItemStack.EMPTY;
        int slot = Equipment.bestMeleeWeaponSlot(companion);
        if (slot < 0) {
            return;
        }
        previousMainHand = companion.getMainHandItem().copy();
        companion.setItemSlot(EquipmentSlot.MAINHAND, companion.inventory().removeItemNoUpdate(slot));
        weaponSwapped = true;
    }

    private void restoreWeapon() {
        if (!weaponSwapped) {
            return;
        }
        ItemStack current = companion.getMainHandItem();
        companion.setItemSlot(EquipmentSlot.MAINHAND, previousMainHand);
        if (!current.isEmpty()) {
            ItemStack leftover = companion.inventory().addItem(current);
            if (!leftover.isEmpty() && companion.level() instanceof ServerLevel level) {
                companion.spawnAtLocation(level, leftover);
            }
        }
        previousMainHand = ItemStack.EMPTY;
        weaponSwapped = false;
    }

    private void tickFight(ServerLevel level, int now) {
        LivingEntity enemy = target;
        if (enemy == null || enemy.isRemoved() || !enemy.isAlive()) {
            endFight(level, enemy);
            return;
        }
        if (enemy instanceof ServerPlayer player && !Threats.mayFightPlayer(companion, player)) {
            endFight(level, enemy);
            return;
        }
        if (companion.distanceTo(enemy) > FIGHT_DROPOFF) {
            endFight(level, enemy);
            return;
        }
        if (companion.hasLineOfSight(enemy)) {
            lastSeenTick = now;
        } else if (now - lastSeenTick > OUT_OF_SIGHT_TICKS) {
            endFight(level, enemy);
            return;
        }

        boolean ranged = Equipment.hasBowAndArrows(companion)
                && (companion.distanceTo(enemy) > RANGED_DISTANCE || isFlying(enemy) || unreachableTicks > 20);
        if (ranged && companion.hasLineOfSight(enemy)) {
            tickRanged(level, enemy);
            return;
        }
        if (usingBow) {
            cancelBow();
            equipMeleeAfterBow();
        }

        companion.getLookControl().setLookAt(enemy, 30.0F, 30.0F);
        if (companion.isWithinMeleeAttackRange(enemy)) {
            companion.getNavigation().stop();
            unreachableTicks = 0;
            if (--attackCooldown <= 0) {
                companion.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
                companion.doHurtTarget(level, enemy);
                attackCooldown = attackInterval();
            }
        } else if (--repathTicks <= 0) {
            unreachableTicks = companion.getNavigation().moveTo(enemy, 1.0) ? 0 : unreachableTicks + 1;
            repathTicks = 10;
        }
    }

    private void tickRanged(ServerLevel level, LivingEntity enemy) {
        companion.getNavigation().stop();
        companion.getLookControl().setLookAt(enemy, 30.0F, 30.0F);
        if (!usingBow) {
            if (!holdBow()) {
                return;
            }
            usingBow = true;
            drawTicks = 0;
            companion.startUsingItem(InteractionHand.MAIN_HAND);
        }
        drawTicks++;
        if (drawTicks < BOW_DRAW_TICKS) {
            return;
        }
        shoot(level, enemy);
        drawTicks = 0;
    }

    private boolean holdBow() {
        if (companion.getMainHandItem().is(Items.BOW)) {
            return true;
        }
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            if (!companion.inventory().getItem(i).is(Items.BOW)) {
                continue;
            }
            ItemStack bow = companion.inventory().removeItemNoUpdate(i);
            ItemStack current = companion.getMainHandItem();
            companion.setItemSlot(EquipmentSlot.MAINHAND, bow);
            if (!current.isEmpty()) {
                ItemStack leftover = companion.inventory().addItem(current);
                if (!leftover.isEmpty() && companion.level() instanceof ServerLevel level) {
                    companion.spawnAtLocation(level, leftover);
                }
            }
            return true;
        }
        return false;
    }

    private void equipMeleeAfterBow() {
        int slot = Equipment.bestMeleeWeaponSlot(companion);
        if (slot < 0) {
            return;
        }
        ItemStack weapon = companion.inventory().removeItemNoUpdate(slot);
        ItemStack current = companion.getMainHandItem();
        companion.setItemSlot(EquipmentSlot.MAINHAND, weapon);
        if (!current.isEmpty()) {
            ItemStack leftover = companion.inventory().addItem(current);
            if (!leftover.isEmpty() && companion.level() instanceof ServerLevel level) {
                companion.spawnAtLocation(level, leftover);
            }
        }
    }

    private void cancelBow() {
        if (usingBow) {
            companion.stopUsingItem();
        }
        usingBow = false;
        drawTicks = 0;
    }

    private void shoot(ServerLevel level, LivingEntity enemy) {
        ItemStack bow = companion.getMainHandItem();
        if (!bow.is(Items.BOW)) {
            cancelBow();
            return;
        }
        AbstractArrow arrow = ProjectileUtil.getMobArrow(companion, new ItemStack(Items.ARROW), ARROW_SPEED, bow);
        Vec3 direction = enemy.getEyePosition().subtract(companion.getEyePosition());
        arrow.shoot(direction.x, direction.y, direction.z, ARROW_SPEED, ARROW_INACCURACY);
        level.addFreshEntity(arrow);
        if (!hasInfinity(level, bow)) {
            consumeArrow();
        }
        bow.hurtAndBreak(1, companion, EquipmentSlot.MAINHAND);
        companion.stopUsingItem();
        usingBow = false;
        drawTicks = 0;
    }

    private static boolean hasInfinity(ServerLevel level, ItemStack bow) {
        return EnchantmentHelper.getItemEnchantmentLevel(
                        level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.INFINITY),
                        bow)
                > 0;
    }

    private void consumeArrow() {
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            if (!companion.inventory().getItem(i).is(Items.ARROW)) {
                continue;
            }
            companion.inventory().removeItem(i, 1);
            return;
        }
    }

    private static boolean isFlying(LivingEntity entity) {
        return entity instanceof Phantom || entity instanceof Blaze || entity instanceof Ghast || entity instanceof Vex;
    }

    private void abandonFight() {
        if (target != null && (target.isDeadOrDying() || target.isRemoved())) {
            kills.merge(nameOf(target), 1, Integer::sum);
        }
        companion.getNavigation().stop();
        companion.stopUsingItem();
        usingBow = false;
        drawTicks = 0;
        restoreWeapon();
        if (!taskDriven) {
            companion.taskRunner().notice(endNotice());
        }
        state = State.IDLE;
        target = null;
        taskDriven = false;
    }

    private int attackInterval() {
        return Math.max(1, (int) Math.round(20.0 / Math.max(0.1, companion.getAttributeValue(Attributes.ATTACK_SPEED))));
    }

    private void endFight(ServerLevel level, LivingEntity ended) {
        if (ended != null && (ended.isDeadOrDying() || ended.isRemoved())) {
            kills.merge(nameOf(ended), 1, Integer::sum);
        }
        companion.collectNearbyItems(DROP_COLLECT_RADIUS);
        companion.getNavigation().stop();
        companion.stopUsingItem();
        usingBow = false;
        drawTicks = 0;
        restoreWeapon();
        state = State.IDLE;
        target = null;
        if (!taskDriven) {
            companion.taskRunner().notice(endNotice());
            companion.taskRunner().setPaused(false);
        }
        taskDriven = false;
    }

    private String startNotice(Selection selection) {
        return switch (selection.kind()) {
            case SELF -> "a " + nameOf(selection.entity()) + " attacked me; fighting back";
            case OWNER -> "a " + nameOf(selection.entity()) + " attacked " + ownerName() + "; defending";
            case GUARD -> "guarding: attacking a " + nameOf(selection.entity());
        };
    }

    private String endNotice() {
        StringBuilder text = new StringBuilder("fight over: ");
        if (kills.isEmpty()) {
            text.append("nothing killed");
        } else {
            text.append("killed ").append(kills.entrySet().stream()
                    .map(kill -> kill.getValue() + " " + kill.getKey())
                    .collect(Collectors.joining(", ")));
        }
        return text.append(", health ")
                .append(number(companion.getHealth()))
                .append('/')
                .append(number(companion.getMaxHealth()))
                .toString();
    }

    private String ownerName() {
        return companion.owner().map(owner -> owner.getName().getString()).orElse("the owner");
    }

    private static String nameOf(LivingEntity entity) {
        if (entity instanceof Player player) {
            return player.getName().getString();
        }
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    private static String number(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
