package dev.friendai.task.combat;

import dev.friendai.combat.CombatDecision;
import dev.friendai.combat.CombatPolicy;
import dev.friendai.combat.CombatSettings;
import dev.friendai.combat.CombatSnapshot;
import dev.friendai.combat.GroupPressure;
import dev.friendai.combat.PotionFacts;
import dev.friendai.combat.PotionPolicy;
import dev.friendai.combat.ProjectileThreat;
import dev.friendai.combat.ProtectionPolicy;
import dev.friendai.combat.ShieldPolicy;
import dev.friendai.combat.ThreatTable;
import dev.friendai.combat.WeaponPolicy;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.movement.Facing;
import dev.friendai.survival.HungerModel;
import dev.friendai.task.TaskContext;
import dev.friendai.task.inventory.DropCollector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
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
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
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
    private static final int OUT_OF_SIGHT_TICKS = 100;
    /** How far from the kill the controller looks for the drops of the fight it just ended. */
    private static final double LOOT_RADIUS = 8.0;

    private static final int BOW_DRAW_TICKS = 20;
    private static final float ARROW_SPEED = 3.0F;
    private static final float ARROW_INACCURACY = 2.0F;
    private static final double CREEPER_FLEE_RADIUS = 5.0;
    private static final double LOW_HEALTH_FRACTION = 0.25;
    private static final double RETREAT_THREAT_RADIUS = 8.0;
    private static final double RETREAT_SAFE_RADIUS = 12.0;
    private static final int RETREAT_MAX_TICKS = 200;
    private static final double FLEE_DISTANCE = 8.0;
    /** How often the held weapon is rescored while fighting. */
    private static final int WEAPON_CHECK_TICKS = 20;
    /** Ticks between allowed splash potion throws. */
    private static final int POTION_COOLDOWN_TICKS = 100;
    /** Splash potions allowed per fight. */
    private static final int POTION_BUDGET = 3;
    /** How far sideways a dodge step goes. */
    private static final double DODGE_STEP = 2.0;
    /** Half-width of the ally corridor around a firing lane. */
    private static final double FIRING_LANE_CLEARANCE = 1.5;
    /** A mob drawing a bow at the companion or the owner inside this radius is a pre-emptive shield threat. */
    private static final double WINDUP_RADIUS = 16.0;
    /** A second hostile this close is close enough to flank. */
    private static final double CROWD_RADIUS = 4.0;

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
    /** The round that walks to the drops of the last fight, ticked while idle. */
    private DropCollector loot;

    private int nextScan;
    private int attackCooldown;
    private int repathTicks;
    private int lastSeenTick;
    private boolean taskDriven;
    /** The current task-driven fight targets the owner, who asked for it, so the self-protection check is waived. */
    private boolean ownerConsent;

    private boolean weaponSwapped;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private boolean usingBow;
    private int drawTicks;
    private int unreachableTicks;
    private double lastDistance = Double.MAX_VALUE;
    private int retreatTicks;
    private int retreatRepathTicks;
    private boolean retreatNoticed;
    private boolean retreatFromCreeper;
    private Vec3 retreatTarget;
    private int armorCheckTicks = 20;
    private int lastArmorHash;
    private ItemStack eatingPrevious = ItemStack.EMPTY;
    private ItemStack eatingItem = ItemStack.EMPTY;
    private int eatTicks;

    private Kind targetKind = Kind.SELF;
    private Vec3 anchor = Vec3.ZERO;
    private WeaponPolicy.Band band = WeaponPolicy.Band.UNKNOWN;
    private boolean shieldSwapped;
    private boolean shieldRaised;
    private ItemStack previousOffhand = ItemStack.EMPTY;
    private int weaponCheckTicks;
    private int potionCooldown;
    private int potionBudget;
    private boolean ammoNoticed;
    private int retargetTicks;
    private int lastHurtTick;

    private CombatDecision decision = CombatDecision.none();

    public CombatController(CompanionEntity companion) {
        this.companion = companion;
    }

    /** The last arbitration result, including {@code NONE} between fights. */
    public CombatDecision decision() {
        return decision;
    }

    public String activeAction() {
        return decision.action().name().toLowerCase(Locale.ROOT);
    }

    public String activeReason() {
        return decision.reason().name().toLowerCase(Locale.ROOT);
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

    /** True while the controller is walking to the drops of the last fight. */
    public boolean looting() {
        return loot != null;
    }

    /** Drops the loot round, leaving whatever is on the ground where it lies. */
    public void stopLooting() {
        stopLootRound();
    }

    private void stopLootRound() {
        if (loot == null) {
            return;
        }
        if (companion.level() instanceof ServerLevel level) {
            loot.stop(new TaskContext(companion, level));
        }
        loot = null;
    }

    /** True while the controller is eating. */
    public boolean isEating() {
        return state == State.EATING;
    }

    /** Starts eating from an inventory slot; false when the controller is busy. */
    public boolean eatFromSlot(int slot) {
        if (state != State.IDLE || slot < 0) {
            return false;
        }
        startEating(slot);
        return state == State.EATING;
    }

    /** Interrupts an eating attempt and returns the food to the inventory. */
    public void stopEating() {
        if (state == State.EATING && companion.level() instanceof ServerLevel level) {
            interruptEating(level);
        }
    }

    /** Ends a fight the tasks asked for, restoring the main hand. */
    public void stopFight() {
        if (state != State.FIGHTING || !taskDriven) {
            return;
        }
        companion.getNavigation().stop();
        companion.stopUsingItem();
        usingBow = false;
        drawTicks = 0;
        lowerShield();
        restoreShield();
        restoreWeapon();
        state = State.IDLE;
        target = null;
        taskDriven = false;
        ownerConsent = false;
        band = WeaponPolicy.Band.UNKNOWN;
        decision = CombatDecision.none();
    }

    /** Starts a fight on a task's behalf: the runner is not paused and the brain gets no notices. */
    public void fight(LivingEntity enemy) {
        fight(enemy, false);
    }

    /**
     * Starts a fight on a task's behalf. {@code ownerConsent} lets the companion hit its own owner, who asked for
     * it in the turn; the owner is never a target otherwise.
     */
    public void fight(LivingEntity enemy, boolean ownerConsent) {
        if (state != State.IDLE
                || enemy == null
                || !enemy.isAlive()
                || !(companion.level() instanceof ServerLevel level)) {
            return;
        }
        beginFight(level, new Selection(enemy, Kind.SELF), true, ownerConsent && isOwner(enemy));
    }

    private boolean isOwner(LivingEntity entity) {
        return companion.owner().map(owner -> owner == entity).orElse(false);
    }

    public void tick() {
        if (!(companion.level() instanceof ServerLevel level) || !companion.isAlive()) {
            return;
        }
        int now = companion.tickCount;
        tickArmor();
        if (companion.creativeMode() && !taskDriven) {
            if (state == State.FIGHTING) {
                abandonFight();
                companion.taskRunner().setPaused(false);
            } else if (state == State.RETREATING) {
                finishRetreat();
            } else if (state == State.EATING) {
                interruptEating(level);
                state = State.IDLE;
                decision = CombatDecision.none();
            }
            return;
        }
        Creeper creeper = swellingCreeper(level);
        if (creeper != null) {
            if (state != State.RETREATING || !retreatFromCreeper) {
                beginRetreat(level, List.of(creeper), true);
            }
            tickRetreat(level, now, List.of(creeper));
            return;
        }
        if (state == State.RETREATING) {
            tickRetreat(
                    level,
                    now,
                    threatsNear(level).stream()
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
        if (state == State.EATING) {
            tickEating(level);
            return;
        }
        if (state == State.FIGHTING) {
            tickFight(level, now);
            return;
        }
        if (state != State.IDLE) {
            return;
        }
        // Walk to what the last fight dropped before looking for the next one. Passively too: the companion still
        // wants the loot of a fight it fought back in. A new fight ends the round through beginFight.
        if (loot != null && loot.tick(new TaskContext(companion, level)) == DropCollector.State.DONE) {
            loot = null;
        }
        if (companion.combatSettings().protection() == ProtectionPolicy.AVOID_COMBAT) {
            decision = new CombatDecision(CombatDecision.Action.AVOID, CombatDecision.Reason.AVOID_COMBAT);
            List<LivingEntity> passiveThreats = ruleOneThreats(level).stream()
                    .filter(threat -> companion.distanceTo(threat) <= RETREAT_SAFE_RADIUS)
                    .toList();
            if (!passiveThreats.isEmpty()) {
                beginRetreat(level, passiveThreats, false);
                tickRetreat(level, now, passiveThreats);
            } else {
                tryEat(level);
            }
            return;
        }
        if (now >= nextScan) {
            nextScan = now + SCAN_INTERVAL;
            Selection selection = selectTarget(level);
            if (selection != null) {
                beginFight(level, selection, false);
                return;
            }
        }
        tryEat(level);
    }

    private void tryEat(ServerLevel level) {
        int food = companion.hunger().food();
        float health = companion.getHealth();
        float maxHealth = companion.getMaxHealth();
        boolean hungry = food <= 14
                || (health < maxHealth && food < HungerModel.MAX_FOOD && food <= 17)
                || (health <= maxHealth * 0.5F && food < HungerModel.MAX_FOOD);
        if (!hungry) {
            return;
        }
        boolean threatClose = ruleOneThreats(level).stream().anyMatch(threat -> companion.distanceTo(threat) <= 3.0);
        if (threatClose) {
            return;
        }
        boolean goldenAllowed =
                health <= maxHealth * 0.3F && !threatsNear(level).isEmpty();
        Food.choose(companion, goldenAllowed).ifPresent(this::startEating);
    }

    private void startEating(int slot) {
        ItemStack food = companion.inventory().removeItemNoUpdate(slot);
        if (food.isEmpty()) {
            return;
        }
        state = State.EATING;
        decision = new CombatDecision(CombatDecision.Action.EAT, CombatDecision.Reason.RECOVER);
        eatingItem = food.copy();
        eatingPrevious = companion.getMainHandItem().copy();
        eatTicks = 0;
        companion.setItemSlot(EquipmentSlot.MAINHAND, food);
        companion.startUsingItem(InteractionHand.MAIN_HAND);
    }

    private void tickEating(ServerLevel level) {
        eatTicks++;
        LivingEntity attacker = companion.getLastHurtByMob();
        boolean hitRecently = attacker != null
                && companion.tickCount - companion.getLastHurtByMobTimestamp() <= 1
                && companion.distanceTo(attacker) <= 3.0;
        if (hitRecently || !companion.isUsingItem()) {
            ItemStack current = companion.getMainHandItem();
            boolean consumed = current.isEmpty() || current.getCount() < eatingItem.getCount();
            if (consumed) {
                FoodProperties food = eatingItem.get(DataComponents.FOOD);
                if (food != null) {
                    companion.hunger().eat(food.nutrition(), food.saturation());
                }
            }
            returnFood(level);
            state = State.IDLE;
            decision = CombatDecision.none();
            return;
        }
        if (eatTicks > 200) {
            companion.stopUsingItem();
            returnFood(level);
            state = State.IDLE;
            decision = CombatDecision.none();
        }
    }

    private void interruptEating(ServerLevel level) {
        if (state != State.EATING) {
            return;
        }
        if (companion.isUsingItem()) {
            companion.stopUsingItem();
        }
        returnFood(level);
        state = State.IDLE;
        decision = CombatDecision.none();
    }

    private void returnFood(ServerLevel level) {
        ItemStack food = companion.getMainHandItem();
        companion.setItemSlot(EquipmentSlot.MAINHAND, eatingPrevious);
        if (!food.isEmpty()) {
            ItemStack leftover = companion.inventory().addItem(food);
            if (!leftover.isEmpty()) {
                companion.spawnAtLocation(level, leftover);
            }
        }
        eatingPrevious = ItemStack.EMPTY;
        eatingItem = ItemStack.EMPTY;
        eatTicks = 0;
    }

    private void tickArmor() {
        if (--armorCheckTicks > 0) {
            return;
        }
        armorCheckTicks = 20;
        int hash = Equipment.armorHash(companion);
        if (hash != lastArmorHash) {
            Equipment.equipBestArmor(companion);
            lastArmorHash = Equipment.armorHash(companion);
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
            if (!threats.contains(hostile)) {
                threats.add(hostile);
            }
        }
        return threats;
    }

    private void beginRetreat(ServerLevel level, List<LivingEntity> threats, boolean fromCreeper) {
        interruptEating(level);
        stopLootRound();
        if (state == State.FIGHTING) {
            abandonFight();
        }
        state = State.RETREATING;
        decision = new CombatDecision(CombatDecision.Action.RETREAT, CombatDecision.Reason.IMMINENT_DANGER);
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
                && owner.position().distanceToSqr(centroid)
                        > companion.position().distanceToSqr(centroid)) {
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
        companion.taskRunner().notice(Food.choose(companion, false).isEmpty() ? text + ", I have no food" : text);
    }

    private void finishRetreat() {
        state = State.IDLE;
        decision = CombatDecision.none();
        retreatTarget = null;
        retreatFromCreeper = false;
        companion.getNavigation().stop();
        companion.taskRunner().setPaused(false);
    }

    private Selection selectTarget(ServerLevel level) {
        CombatSettings settings = companion.combatSettings();
        if (settings.protection() == ProtectionPolicy.AVOID_COMBAT) {
            return null;
        }
        List<Scored> scored = threatCandidates(level, settings);
        if (scored.isEmpty()) {
            return null;
        }
        List<ThreatTable.Candidate> facts = new ArrayList<>(scored.size());
        for (Scored candidate : scored) {
            facts.add(candidate.facts());
        }
        int best = ThreatTable.best(facts, settings.pursuitLimitBlocks());
        if (best < 0) {
            return null;
        }
        Scored chosen = scored.get(best);
        return new Selection(chosen.entity(), chosen.kind());
    }

    /** One believable threat and the facts the ranking needs. */
    private record Scored(LivingEntity entity, Kind kind, ThreatTable.Candidate facts) {}

    /**
     * Every entity that may be fought now, deduplicated with the first kind winning: the last attacker, the
     * owner's attacker, mobs targeting either, then hostile mobs near the guarded area.
     */
    private List<Scored> threatCandidates(ServerLevel level, CombatSettings settings) {
        List<Scored> scored = new ArrayList<>();
        // Before a fight starts the stored anchor is still unset; score against the protected anchor now.
        Vec3 pursuitAnchor = state == State.FIGHTING ? anchor : currentAnchor(level);
        LivingEntity attacker = companion.getLastHurtByMob();
        if (validAttacker(attacker) && companion.tickCount - companion.getLastHurtByMobTimestamp() <= ATTACKER_MEMORY) {
            addCandidate(scored, attacker, Kind.SELF, true, pursuitAnchor);
        }

        ServerPlayer owner = companion.owner().filter(o -> o.level() == level).orElse(null);
        if (owner != null
                && companion.distanceTo(owner) <= OWNER_DEFENSE_RANGE
                && settings.protection().defendsOwner()) {
            LivingEntity ownerAttacker = owner.getLastHurtByMob();
            if (validAttacker(ownerAttacker)
                    && owner.tickCount - owner.getLastHurtByMobTimestamp() <= ATTACKER_MEMORY) {
                addCandidate(scored, ownerAttacker, Kind.OWNER, false, pursuitAnchor);
            }
        }

        for (LivingEntity entity : level.getEntitiesOfClass(
                LivingEntity.class, new AABB(companion.position(), companion.position()).inflate(TARGETING_RANGE))) {
            if (!validAttacker(entity) || !(entity instanceof Mob mob)) {
                continue;
            }
            if (mob.getTarget() == companion) {
                addCandidate(scored, entity, Kind.SELF, false, pursuitAnchor);
            } else if (owner != null
                    && mob.getTarget() == owner
                    && settings.protection().defendsOwner()) {
                addCandidate(scored, entity, Kind.OWNER, false, pursuitAnchor);
            }
        }

        if (settings.protection().guardsArea()) {
            Vec3 center = owner != null ? owner.position() : companion.position();
            for (LivingEntity entity : Threats.hostilesNear(level, center, GUARD_RADIUS)) {
                if (!Threats.isProtected(entity, companion) && companion.hasLineOfSight(entity)) {
                    addCandidate(scored, entity, Kind.GUARD, false, pursuitAnchor);
                }
            }
        }
        return scored;
    }

    private void addCandidate(
            List<Scored> scored, LivingEntity entity, Kind kind, boolean attackedUs, Vec3 pursuitAnchor) {
        for (Scored existing : scored) {
            if (existing.entity() == entity) {
                return;
            }
        }
        double dx = entity.getX() - companion.getX();
        double dz = entity.getZ() - companion.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        double vertical = Math.abs(entity.getY() - companion.getY());
        boolean reachesNow =
                companion.isWithinMeleeAttackRange(entity) || (distance <= meleeReach(entity) && vertical <= 2.0);
        scored.add(new Scored(
                entity,
                kind,
                new ThreatTable.Candidate(
                        distance,
                        reachesNow,
                        attackedUs,
                        entity == target,
                        entity.position().distanceTo(pursuitAnchor))));
    }

    /** Drops the old target's per-target state and stops a draw that no longer belongs to the fight. */
    private void switchTarget(Selection selection) {
        target = selection.entity();
        targetKind = selection.kind();
        lastSeenTick = companion.tickCount;
        unreachableTicks = 0;
        lastDistance = Double.MAX_VALUE;
        band = WeaponPolicy.Band.UNKNOWN;
        attackCooldown = 0;
        repathTicks = 0;
        companion.getNavigation().stop();
        if (usingBow || companion.getMainHandItem().is(Items.BOW)) {
            cancelBow();
            swapToMelee();
        }
        decision = new CombatDecision(CombatDecision.Action.MELEE_APPROACH, reasonFor(targetKind));
    }

    /** The protected anchor now: where the owner stands, or the companion when there is no owner in this level. */
    private Vec3 currentAnchor(ServerLevel level) {
        return companion
                .owner()
                .filter(owner -> owner.level() == level)
                .map(owner -> owner.position())
                .orElse(companion.position());
    }

    /** True while the current reflex engagement is still allowed by the owner's protection policy. */
    private boolean stillPermitted(Kind kind) {
        return switch (kind) {
            case SELF -> true;
            case OWNER -> companion.combatSettings().protection().defendsOwner();
            case GUARD -> companion.combatSettings().protection().guardsArea();
        };
    }

    private static CombatDecision.Reason reasonFor(Kind kind) {
        return switch (kind) {
            case SELF -> CombatDecision.Reason.DEFEND_SELF;
            case OWNER -> CombatDecision.Reason.DEFEND_OWNER;
            case GUARD -> CombatDecision.Reason.GUARD;
        };
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
        beginFight(level, selection, ordered, false);
    }

    private void beginFight(ServerLevel level, Selection selection, boolean ordered, boolean ownerConsent) {
        state = State.FIGHTING;
        stopLootRound();
        target = selection.entity();
        targetKind = selection.kind();
        taskDriven = ordered;
        this.ownerConsent = ownerConsent;
        attackCooldown = 0;
        repathTicks = 0;
        unreachableTicks = 0;
        lastDistance = Double.MAX_VALUE;
        drawTicks = 0;
        usingBow = false;
        lastSeenTick = companion.tickCount;
        kills.clear();
        anchor = companion
                .owner()
                .filter(owner -> owner.level() == level)
                .map(owner -> owner.position())
                .orElse(companion.position());
        band = WeaponPolicy.Band.UNKNOWN;
        weaponCheckTicks = 0;
        potionCooldown = 0;
        potionBudget = POTION_BUDGET;
        ammoNoticed = false;
        retargetTicks = SCAN_INTERVAL;
        lastHurtTick = companion.getLastHurtByMobTimestamp();
        decision = new CombatDecision(CombatDecision.Action.MELEE_APPROACH, reasonFor(selection.kind()));
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

    /** True when a shield is in the offhand or anywhere in the inventory. */
    private boolean shieldAvailable() {
        if (companion.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)) {
            return true;
        }
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            if (companion.inventory().getItem(i).is(Items.SHIELD)) {
                return true;
            }
        }
        return false;
    }

    /** Moves a shield to the offhand, preserving whatever was there. */
    private void equipShield() {
        if (shieldSwapped || companion.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)) {
            return;
        }
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            if (!companion.inventory().getItem(i).is(Items.SHIELD)) {
                continue;
            }
            previousOffhand = companion.getItemBySlot(EquipmentSlot.OFFHAND).copy();
            companion.setItemSlot(EquipmentSlot.OFFHAND, companion.inventory().removeItemNoUpdate(i));
            shieldSwapped = true;
            return;
        }
    }

    private void raiseShield() {
        equipShield();
        if (!companion.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)) {
            return;
        }
        if (!companion.isUsingItem()) {
            companion.startUsingItem(InteractionHand.OFF_HAND);
        }
        // A bow draw holds the use slot; only report the shield as raised when it is really the active item.
        shieldRaised = companion.isUsingItem()
                && companion.getItemInHand(companion.getUsedItemHand()).is(Items.SHIELD);
    }

    private void lowerShield() {
        if (!shieldRaised) {
            return;
        }
        companion.stopUsingItem();
        shieldRaised = false;
    }

    /** Returns the displaced offhand stack, spawning overflow rather than dropping it silently. */
    private void restoreShield() {
        lowerShield();
        if (!shieldSwapped) {
            return;
        }
        ItemStack current = companion.getItemBySlot(EquipmentSlot.OFFHAND);
        companion.setItemSlot(EquipmentSlot.OFFHAND, previousOffhand);
        if (!current.isEmpty()) {
            ItemStack leftover = companion.inventory().addItem(current);
            if (!leftover.isEmpty() && companion.level() instanceof ServerLevel level) {
                companion.spawnAtLocation(level, leftover);
            }
        }
        previousOffhand = ItemStack.EMPTY;
        shieldSwapped = false;
    }

    private void tickFight(ServerLevel level, int now) {
        LivingEntity enemy = target;
        if (enemy == null || enemy.isRemoved() || !enemy.isAlive()) {
            endFight(level, enemy);
            return;
        }
        // A settings change revalidates the reflex engagement immediately.
        if (!taskDriven && companion.combatSettings().protection() == ProtectionPolicy.AVOID_COMBAT) {
            endFight(level, enemy);
            return;
        }
        if (!taskDriven && !stillPermitted(targetKind)) {
            endFight(level, enemy);
            return;
        }
        if (enemy instanceof ServerPlayer player
                && !Threats.mayFightPlayer(companion, player)
                && !(ownerConsent && isOwner(player))) {
            endFight(level, enemy);
            return;
        }
        if (!validAttacker(enemy) && !(ownerConsent && isOwner(enemy))) {
            endFight(level, enemy);
            return;
        }
        if (enemy.position().distanceTo(anchor) > companion.combatSettings().pursuitLimitBlocks()) {
            decision = new CombatDecision(CombatDecision.Action.DISENGAGE, CombatDecision.Reason.OUT_OF_PURSUIT);
            endFight(level, enemy);
            return;
        }
        if (companion.hasLineOfSight(enemy)) {
            lastSeenTick = now;
        } else if (now - lastSeenTick > OUT_OF_SIGHT_TICKS) {
            endFight(level, enemy);
            return;
        }

        // A nearer attacker that lands a hit becomes the target without waiting for the next scan.
        int hurtTick = companion.getLastHurtByMobTimestamp();
        if (!taskDriven && hurtTick != lastHurtTick) {
            lastHurtTick = hurtTick;
            retargetTicks = 0;
        }
        if (!taskDriven && --retargetTicks <= 0) {
            retargetTicks = SCAN_INTERVAL;
            Selection better = selectTarget(level);
            if (better != null && better.entity() != enemy) {
                switchTarget(better);
                enemy = target;
            }
        }

        companion.facing().lookAt(enemy, 2);
        double distance = companion.distanceTo(enemy);
        double reach = meleeReach(enemy);
        boolean inReach = companion.isWithinMeleeAttackRange(enemy) || distance * distance <= 3.0625;

        // Physical danger is independent of protective eligibility: every preset may block, dodge or deflect.
        // A shooter's draw raises the shield before the arrow exists; an arrow in flight always wins over a swing.
        ProjectileThreat.Facts incoming = incomingProjectile(level);
        LivingEntity windup = rangedWindup(level);
        boolean frontal = incoming != null ? incoming.frontal() : windup != null;
        ShieldPolicy.Need shield = ShieldPolicy.decide(
                shieldAvailable(), shieldRaised, incoming != null, windup != null, frontal, inReach);
        if (shield != ShieldPolicy.Need.LOWER) {
            if (usingBow) {
                // The shield and the bow share the use slot; a draw must yield to the block.
                cancelBow();
            }
            if (windup != null) {
                companion.facing().lookAt(windup, 2);
            } else if (incoming != null) {
                face(incoming);
            }
            raiseShield();
            decision = new CombatDecision(CombatDecision.Action.SHIELD, CombatDecision.Reason.BLOCK_PROJECTILE);
            return;
        }
        lowerShield();
        if (incoming != null && tryDodge(level, incoming)) {
            decision = new CombatDecision(CombatDecision.Action.DODGE, CombatDecision.Reason.EVADE_PROJECTILE);
            return;
        }

        if (--weaponCheckTicks <= 0) {
            weaponCheckTicks = WEAPON_CHECK_TICKS;
            if (!usingBow) {
                refillWeapon();
            }
        }

        if (tickPotion(level, enemy, windup != null)) {
            return;
        }

        boolean bowHeld = companion.getMainHandItem().is(Items.BOW);
        boolean ranged = shouldUseBow(enemy, bowHeld);
        if (ranged && companion.hasLineOfSight(enemy)) {
            decision = new CombatDecision(CombatDecision.Action.FIRE_BOW, reasonFor(targetKind));
            tickRanged(level, enemy);
            return;
        }
        if (usingBow) {
            cancelBow();
        }
        if (!ranged && bowHeld) {
            swapToMelee();
        }

        if (distance + 0.05 < lastDistance) {
            lastDistance = distance;
            unreachableTicks = 0;
        } else {
            unreachableTicks++;
        }
        if (unreachableTicks > OUT_OF_SIGHT_TICKS && !Equipment.hasBowAndArrows(companion)) {
            endFight(level, enemy);
            return;
        }
        double distanceSqr = distance * distance;
        band = WeaponPolicy.band(distance, reach, band);
        if (inReach) {
            lowerShield();
            companion.getNavigation().stop();
            unreachableTicks = 0;
            lastDistance = distance;
            if (--attackCooldown <= 0) {
                // Permission is rechecked at the action, not only when the target was selected.
                if (!validAttacker(enemy) && !(ownerConsent && isOwner(enemy))) {
                    endFight(level, enemy);
                    return;
                }
                decision = new CombatDecision(CombatDecision.Action.ATTACK, reasonFor(targetKind));
                companion.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
                if (companion.doHurtTarget(level, enemy)) {
                    companion.hunger().addExhaustion(CompanionEntity.EXHAUSTION_ATTACK);
                }
                attackCooldown = attackInterval();
                crowdStep(level, enemy);
            } else {
                decision = new CombatDecision(CombatDecision.Action.ATTACK, reasonFor(targetKind));
                // Cautious fighters raise the shield between swings.
                if (companion.combatSettings().style() == dev.friendai.combat.FightingStyle.CAUTIOUS
                        && shieldAvailable()) {
                    raiseShield();
                    decision = new CombatDecision(CombatDecision.Action.SHIELD, CombatDecision.Reason.POSITION);
                }
            }
        } else if (companion.combatSettings().style() == dev.friendai.combat.FightingStyle.RANGED
                && Equipment.hasBowAndArrows(companion)
                && distance < 5.0
                && backOff(level, enemy)) {
            decision = new CombatDecision(CombatDecision.Action.KEEP_RANGE, CombatDecision.Reason.STYLE_RANGE);
        } else if (distanceSqr <= 9.0) {
            // Pathfinding stops just outside the reach of a small mob; the move control closes the last step.
            decision = new CombatDecision(CombatDecision.Action.MELEE_APPROACH, reasonFor(targetKind));
            companion.getNavigation().stop();
            companion.getMoveControl().setWantedPosition(enemy.getX(), enemy.getY(), enemy.getZ(), 1.2);
        } else if (--repathTicks <= 0) {
            decision = new CombatDecision(CombatDecision.Action.MELEE_APPROACH, reasonFor(targetKind));
            companion.getNavigation().moveTo(enemy, 1.0);
            repathTicks = 10;
        } else {
            decision = new CombatDecision(CombatDecision.Action.MELEE_APPROACH, reasonFor(targetKind));
        }
    }

    private void tickRanged(ServerLevel level, LivingEntity enemy) {
        lowerShield();
        companion.getNavigation().stop();
        companion.facing().lookAt(enemy, 2);
        if (usingBow && !companion.getMainHandItem().is(Items.BOW)) {
            // The bow broke mid-draw: stop using the stale stack and fall back to melee.
            cancelBow();
            swapToMelee();
            decision = new CombatDecision(CombatDecision.Action.MELEE_APPROACH, CombatDecision.Reason.NO_AMMUNITION);
            return;
        }
        if (!usingBow) {
            if (!holdBow()) {
                noticeAmmunition();
                decision =
                        new CombatDecision(CombatDecision.Action.MELEE_APPROACH, CombatDecision.Reason.NO_AMMUNITION);
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
        if (!releaseAllowed(level, enemy)) {
            cancelBow();
            decision = new CombatDecision(CombatDecision.Action.KEEP_RANGE, CombatDecision.Reason.UNSAFE_LANE);
            return;
        }
        shoot(level, enemy);
        drawTicks = 0;
    }

    /** Rechecks target eligibility, obstacle occlusion and allies immediately before spawning an arrow. */
    private boolean releaseAllowed(ServerLevel level, LivingEntity enemy) {
        if (!enemy.isAlive() || !validAttacker(enemy)) {
            return false;
        }
        if (!companion.getMainHandItem().is(Items.BOW) || companion.inventory().countItem(Items.ARROW) <= 0) {
            return false;
        }
        return companion.hasLineOfSight(enemy) && !allyInLane(level, enemy);
    }

    private void noticeAmmunition() {
        if (ammoNoticed) {
            return;
        }
        ammoNoticed = true;
        companion.taskRunner().notice("out of arrows; switching to melee");
    }

    private double meleeReach(LivingEntity enemy) {
        double reach = 3.0;
        if (enemy != null) {
            reach = Math.max(reach, companion.getBbWidth() + enemy.getBbWidth() + 0.5);
        }
        return reach;
    }

    private void refillWeapon() {
        int slot = Equipment.bestMeleeWeaponSlot(companion);
        if (slot >= 0) {
            Equipment.hold(companion, slot);
        }
    }

    /** Moves away from a closing melee target only to a checked safe step. */
    private boolean backOff(ServerLevel level, LivingEntity enemy) {
        Vec3 away = companion.position().subtract(enemy.position());
        if (away.horizontalDistanceSqr() < 1.0E-4) {
            away = new Vec3(1, 0, 0);
        }
        return moveToSafe(level, companion.position().add(away.normalize().scale(DODGE_STEP)));
    }

    private boolean moveToSafe(ServerLevel level, Vec3 destination) {
        if (!safeStep(level, destination)) {
            return false;
        }
        companion.getNavigation().moveTo(destination.x, destination.y, destination.z, 1.2);
        return true;
    }

    /** One direct step for a caller that keeps stopping the navigation (the melee branch does). */
    private boolean stepToSafe(ServerLevel level, Vec3 destination) {
        if (!safeStep(level, destination)) {
            return false;
        }
        companion.getMoveControl().setWantedPosition(destination.x, destination.y, destination.z, 1.2);
        return true;
    }

    private boolean safeStep(ServerLevel level, Vec3 destination) {
        var box =
                companion.getBoundingBox().move(destination.x - companion.getX(), 0, destination.z - companion.getZ());
        if (!level.noCollision(companion, box)) {
            return false;
        }
        var feet = net.minecraft.core.BlockPos.containing(destination);
        var below = feet.below();
        var floor = level.getBlockState(below);
        if (floor.getCollisionShape(level, below).isEmpty()
                || !floor.getFluidState().isEmpty()) {
            return false;
        }
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above())
                        .getCollisionShape(level, feet.above())
                        .isEmpty();
    }

    private boolean tryDodge(ServerLevel level, ProjectileThreat.Facts projectile) {
        double vx = projectile.velocityX();
        double vz = projectile.velocityZ();
        double length = Math.sqrt(vx * vx + vz * vz);
        if (length < 1.0E-6) {
            return false;
        }
        double nx = -vz / length;
        double nz = vx / length;
        Vec3 left = companion.position().add(nx * DODGE_STEP, 0, nz * DODGE_STEP);
        Vec3 right = companion.position().add(-nx * DODGE_STEP, 0, -nz * DODGE_STEP);
        return moveToSafe(level, left) || moveToSafe(level, right);
    }

    private void face(ProjectileThreat.Facts projectile) {
        double dx = projectile.x() - companion.getX();
        double dz = projectile.z() - companion.getZ();
        if (dx * dx + dz * dz < 1.0) {
            // The arrow is already on top of the body: its direction is degenerate, keep the current facing.
            return;
        }
        companion.facing().lookAt(projectile.x(), projectile.y(), projectile.z(), 2);
    }

    /** The nearest projectile whose bounded path intersects the companion. */
    private ProjectileThreat.Facts incomingProjectile(ServerLevel level) {
        ProjectileThreat.Facts best = null;
        double bestDistance = Double.MAX_VALUE;
        for (var projectile : level.getEntitiesOfClass(
                net.minecraft.world.entity.projectile.Projectile.class,
                companion.getBoundingBox().inflate(16.0),
                p -> p.isAlive() && !p.isRemoved() && p.getOwner() != companion)) {
            double radius = projectile instanceof AbstractArrow ? 0.1 : 0.5;
            var bounds = new ProjectileThreat.EntityBounds(
                    companion.getBoundingBox().minX,
                    companion.getBoundingBox().minY,
                    companion.getBoundingBox().minZ,
                    companion.getBoundingBox().maxX,
                    companion.getBoundingBox().maxY,
                    companion.getBoundingBox().maxZ);
            Vec3 velocity = projectile.getDeltaMovement();
            var facts = ProjectileThreat.Facts.of(
                    projectile.getX(),
                    projectile.getY(),
                    projectile.getZ(),
                    velocity.x,
                    velocity.y,
                    velocity.z,
                    radius,
                    ProjectileThreat.isFrontal(
                            projectile.getX(),
                            projectile.getZ(),
                            companion.getX(),
                            companion.getZ(),
                            companion.getLookAngle().x,
                            companion.getLookAngle().z),
                    BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).toString());
            if (!ProjectileThreat.intersects(facts, bounds, CombatPolicy.PROJECTILE_HORIZON_TICKS)) {
                continue;
            }
            double distance = projectile.distanceToSqr(companion);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = facts;
            }
        }
        return best;
    }

    private boolean shouldUseBow(LivingEntity enemy, boolean bowHeld) {
        if (!Equipment.hasBowAndArrows(companion) || !allyInLaneSafe(enemy)) {
            return false;
        }
        return WeaponPolicy.bowWanted(
                companion.combatSettings().style(),
                companion.distanceTo(enemy),
                isFlying(enemy),
                unreachableTicks > 20,
                bowHeld);
    }

    /**
     * The nearest valid mob drawing a bow or crossbow at the companion or the owner, inside the windup radius. The
     * vanilla ranged goal draws for about a second through {@code startUsingItem}, which is the warning this reads.
     */
    private LivingEntity rangedWindup(ServerLevel level) {
        ServerPlayer owner = companion.owner().filter(o -> o.level() == level).orElse(null);
        LivingEntity best = null;
        double bestDistance = WINDUP_RADIUS * WINDUP_RADIUS;
        for (LivingEntity entity : level.getEntitiesOfClass(
                LivingEntity.class, companion.getBoundingBox().inflate(WINDUP_RADIUS))) {
            if (!(entity instanceof Mob mob) || !validAttacker(entity)) {
                continue;
            }
            if (mob.getTarget() != companion && (owner == null || mob.getTarget() != owner)) {
                continue;
            }
            ItemStack useItem = mob.getUseItem();
            if (!mob.isUsingItem() || !(useItem.is(Items.BOW) || useItem.is(Items.CROSSBOW))) {
                continue;
            }
            if (!companion.hasLineOfSight(entity)) {
                continue;
            }
            double distance = companion.distanceToSqr(entity);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    /** One bounded sidestep after a swing when a second hostile is close enough to flank. */
    private void crowdStep(ServerLevel level, LivingEntity enemy) {
        LivingEntity other = nearestOtherThreat(level, enemy);
        if (other == null) {
            return;
        }
        GroupPressure.Step step = GroupPressure.sidestep(
                companion.getX(), companion.getZ(),
                enemy.getX(), enemy.getZ(),
                other.getX(), other.getZ());
        stepToSafe(level, companion.position().add(step.dx() * DODGE_STEP, 0.0, step.dz() * DODGE_STEP));
    }

    private LivingEntity nearestOtherThreat(ServerLevel level, LivingEntity enemy) {
        LivingEntity best = null;
        double bestDistance = CROWD_RADIUS * CROWD_RADIUS;
        for (LivingEntity other : threatsNear(level)) {
            if (other == enemy || !validAttacker(other) || Threats.isProtected(other, companion)) {
                continue;
            }
            double distance = companion.distanceToSqr(other);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = other;
            }
        }
        return best;
    }

    /** Cheap pre-check: the full lane test runs again at release. */
    private boolean allyInLaneSafe(LivingEntity enemy) {
        return companion.level() instanceof ServerLevel level && !allyInLane(level, enemy);
    }

    private boolean allyInLane(ServerLevel level, LivingEntity enemy) {
        Vec3 from = companion.getEyePosition();
        Vec3 to = enemy.getEyePosition();
        for (LivingEntity entity : level.getEntitiesOfClass(
                LivingEntity.class, companion.getBoundingBox().inflate(24.0))) {
            if (entity == companion || entity == enemy || !entity.isAlive()) {
                continue;
            }
            if (!Threats.isProtected(entity, companion)) {
                continue;
            }
            if (distanceToSegment(entity.position().add(0, entity.getBbHeight() * 0.5, 0), from, to)
                    <= FIRING_LANE_CLEARANCE) {
                return true;
            }
        }
        return false;
    }

    private static double distanceToSegment(Vec3 point, Vec3 start, Vec3 end) {
        Vec3 direction = end.subtract(start);
        double lengthSqr = direction.lengthSqr();
        if (lengthSqr < 1.0E-9) {
            return point.distanceTo(start);
        }
        double t = Math.max(0.0, Math.min(1.0, point.subtract(start).dot(direction) / lengthSqr));
        return point.distanceTo(start.add(direction.scale(t)));
    }

    private boolean tickPotion(ServerLevel level, LivingEntity enemy, boolean windup) {
        if (potionCooldown > 0) {
            potionCooldown--;
        }
        CombatSettings settings = companion.combatSettings();
        if (!settings.allowSplashPotions() || potionCooldown > 0 || potionBudget <= 0) {
            return false;
        }
        double distance = companion.distanceTo(enemy);
        if (distance < 3.0 || distance > 10.0 || !companion.hasLineOfSight(enemy)) {
            return false;
        }
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            ItemStack stack = companion.inventory().getItem(i);
            if (!stack.is(Items.SPLASH_POTION)) {
                continue;
            }
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents == null) {
                continue;
            }
            boolean harmful = false;
            boolean beneficial = false;
            int shortest = Integer.MAX_VALUE;
            for (MobEffectInstance effect : contents.getAllEffects()) {
                MobEffectCategory category = effect.getEffect().value().getCategory();
                if (category == MobEffectCategory.HARMFUL) {
                    harmful = true;
                } else if (category == MobEffectCategory.BENEFICIAL) {
                    beneficial = true;
                }
                if (!effect.getEffect().value().isInstantaneous()) {
                    shortest = Math.min(shortest, effect.getDuration());
                }
            }
            if (!harmful) {
                return false;
            }
            if (shortest == Integer.MAX_VALUE) {
                shortest = PotionFacts.MIN_USEFUL_DURATION;
            }
            PotionFacts facts = new PotionFacts(
                    harmful,
                    beneficial,
                    harmful && beneficial,
                    shortest,
                    protectedNear(level, enemy.position(), enemy),
                    1,
                    true,
                    true,
                    potionBudget);
            if (!PotionPolicy.evaluate(facts, snapshotFor(level, enemy, windup), settings)
                    .allowed()) {
                return false;
            }
            throwPotion(level, enemy, i, stack);
            return true;
        }
        return false;
    }

    private int protectedNear(ServerLevel level, Vec3 center, LivingEntity enemy) {
        int count = 0;
        for (LivingEntity entity :
                level.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(4.0))) {
            if (entity == companion || entity == enemy || !entity.isAlive()) {
                continue;
            }
            if (Threats.isProtected(entity, companion)) {
                count++;
            }
        }
        // Players are not part of the entity-section query on every level implementation; check them directly.
        for (ServerPlayer player : level.players()) {
            if (player == enemy || !player.isAlive()) {
                continue;
            }
            if (player.position().distanceTo(center) <= 4.0) {
                count++;
            }
        }
        return count;
    }

    private CombatSnapshot snapshotFor(ServerLevel level, LivingEntity enemy, boolean windup) {
        return new CombatSnapshot(
                new CombatSnapshot.Self(
                        companion.getHealth() / Math.max(1.0F, companion.getMaxHealth()),
                        false,
                        false,
                        false,
                        shieldAvailable(),
                        shieldRaised,
                        false,
                        false,
                        false,
                        false,
                        Equipment.hasBowAndArrows(companion),
                        false,
                        false,
                        true,
                        true,
                        true,
                        potionBudget,
                        windup),
                new CombatSnapshot.Target(
                        true,
                        companion.hasLineOfSight(enemy),
                        validAttacker(enemy),
                        targetKind == Kind.SELF,
                        targetKind == Kind.OWNER,
                        enemy instanceof net.minecraft.world.entity.monster.Enemy,
                        companion.distanceTo(enemy),
                        enemy.position().distanceTo(anchor),
                        isFlying(enemy),
                        false),
                new CombatSnapshot.Options(companion.isWithinMeleeAttackRange(enemy), 0.0, true));
    }

    private void throwPotion(ServerLevel level, LivingEntity enemy, int slot, ItemStack stack) {
        companion.facing().lookAt(enemy, 2);
        Vec3 eye = companion.getEyePosition();
        Vec3 target = enemy.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        ThrownSplashPotion potion = new ThrownSplashPotion(level, companion, stack.copy());
        potion.shootFromRotation(
                companion, Facing.pitchToward(dx, dy, dz), Facing.yawToward(dx, dz), -20.0F, 0.7F, 1.0F);
        level.addFreshEntity(potion);
        companion.inventory().removeItem(slot, 1);
        potionCooldown = POTION_COOLDOWN_TICKS;
        potionBudget--;
        decision = new CombatDecision(CombatDecision.Action.THROW_POTION, reasonFor(targetKind));
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

    private void swapToMelee() {
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
        if (!bow.is(Items.BOW) || companion.inventory().countItem(Items.ARROW) <= 0) {
            cancelBow();
            return;
        }
        AbstractArrow arrow = ProjectileUtil.getMobArrow(companion, new ItemStack(Items.ARROW), ARROW_SPEED, bow);
        Vec3 eye = companion.getEyePosition();
        Vec3 velocity = enemy.getDeltaMovement();
        CombatPolicy.Lead lead = CombatPolicy.leadPoint(
                enemy.getX(),
                enemy.getEyeY(),
                enemy.getZ(),
                velocity.x,
                velocity.y,
                velocity.z,
                eye.x,
                eye.y,
                eye.z,
                ARROW_SPEED,
                CombatPolicy.MAX_LEAD_TICKS);
        Vec3 direction = new Vec3(lead.x(), lead.y(), lead.z()).subtract(eye);
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
                        level.registryAccess()
                                .lookupOrThrow(Registries.ENCHANTMENT)
                                .getOrThrow(Enchantments.INFINITY),
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
        restoreShield();
        restoreWeapon();
        decision = CombatDecision.none();
        band = WeaponPolicy.Band.UNKNOWN;
        if (!taskDriven) {
            companion.taskRunner().notice(endNotice());
        }
        state = State.IDLE;
        target = null;
        taskDriven = false;
        ownerConsent = false;
        unreachableTicks = 0;
        lastDistance = Double.MAX_VALUE;
    }

    private int attackInterval() {
        return Math.max(1, (int)
                Math.round(20.0 / Math.max(0.1, companion.getAttributeValue(Attributes.ATTACK_SPEED))));
    }

    private void endFight(ServerLevel level, LivingEntity ended) {
        if (ended != null && (ended.isDeadOrDying() || ended.isRemoved())) {
            kills.merge(nameOf(ended), 1, Integer::sum);
        }
        // The loot is on the ground where the fight ended: walk to it once the fight is over.
        loot = new DropCollector(companion.position(), (int) LOOT_RADIUS);
        companion.getNavigation().stop();
        companion.stopUsingItem();
        usingBow = false;
        drawTicks = 0;
        restoreShield();
        restoreWeapon();
        decision = CombatDecision.none();
        band = WeaponPolicy.Band.UNKNOWN;
        state = State.IDLE;
        target = null;
        unreachableTicks = 0;
        lastDistance = Double.MAX_VALUE;
        if (!taskDriven) {
            companion.taskRunner().notice(endNotice());
            companion.taskRunner().setPaused(false);
        }
        taskDriven = false;
        ownerConsent = false;
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
            text.append("killed ")
                    .append(kills.entrySet().stream()
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
