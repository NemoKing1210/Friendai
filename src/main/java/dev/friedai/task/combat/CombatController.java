package dev.friedai.task.combat;

import dev.friedai.entity.CompanionEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
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
        if (state == State.FIGHTING) {
            tickFight(level, now);
            return;
        }
        if (state != State.IDLE) {
            return;
        }
        if (now < nextScan) {
            return;
        }
        nextScan = now + SCAN_INTERVAL;
        if (companion.combatMode() == CombatMode.PASSIVE) {
            return;
        }
        Selection selection = selectTarget(level);
        if (selection != null) {
            beginFight(level, selection, false);
        }
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
        lastSeenTick = companion.tickCount;
        kills.clear();
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

        companion.getLookControl().setLookAt(enemy, 30.0F, 30.0F);
        if (companion.isWithinMeleeAttackRange(enemy)) {
            companion.getNavigation().stop();
            if (--attackCooldown <= 0) {
                companion.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
                companion.doHurtTarget(level, enemy);
                attackCooldown = attackInterval();
            }
        } else if (--repathTicks <= 0) {
            companion.getNavigation().moveTo(enemy, 1.0);
            repathTicks = 10;
        }
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
