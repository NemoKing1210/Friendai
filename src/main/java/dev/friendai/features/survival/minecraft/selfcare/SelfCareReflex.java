package dev.friendai.features.survival.minecraft.selfcare;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.fire.minecraft.RecoverWaterTask;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.survival.selfcare.SelfCareDecision;
import dev.friendai.features.survival.selfcare.SelfCareFacts;
import dev.friendai.features.survival.selfcare.SelfCarePlanner;
import dev.friendai.task.TaskExecutionPort;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Spends the companion's own emergency items before the task runner ticks, like combat and fire: a falling body pours
 * its water bucket and takes the source back, a poisoned one drinks milk, a critical one eats a golden apple, a
 * drowning one drinks water breathing, and a splash potion heals the owner standing next to it.
 *
 * <p>One decision per tick, one item per emergency. Consumables hold the runner for the few ticks vanilla needs to
 * drink or eat, so the task cannot take the item out of the hand; the combat controller is the other consumer of the
 * same hands and the two never act at once ({@link SelfCareReflex#busy()} and {@code CombatController.busy()}).
 */
public final class SelfCareReflex {
    /** Long enough that a refused pour cannot cost a bucket every tick. */
    public static final int WATER_DROP_COOLDOWN_TICKS = 100;

    /** Long enough that a lost attempt does not consume the whole bag in a second. */
    public static final int CONSUME_COOLDOWN_TICKS = 200;

    /** How long the landing source may stay behind before a task walks back for it. */
    public static final int WATER_RECOVERY_TICKS = 100;

    private static final String FIRE_TOOL = "extinguish_fire";
    private static final String RECOVER_TOOL = "recover_water";

    private final CompanionEntity companion;
    private final SelfCareUse use = new SelfCareUse();

    private int waterCooldown;
    private int consumeCooldown;
    private @Nullable BlockPos pendingWater;
    private int pendingTicks;

    public SelfCareReflex(CompanionEntity companion) {
        this.companion = companion;
    }

    /** True while an item is being drunk or eaten, so no other consumer may touch the hands. */
    public boolean busy() {
        return use.busy();
    }

    public void tick() {
        if (waterCooldown > 0) {
            waterCooldown--;
        }
        if (consumeCooldown > 0) {
            consumeCooldown--;
        }
        if (!companion.isAlive() || !(companion.level() instanceof ServerLevel)) {
            return;
        }
        if (use.tick(companion)) {
            return;
        }
        recoverWater();
        if (!inControl()) {
            return;
        }
        if (!maySpend()) {
            return;
        }
        SelfCareHands hands = new SelfCareHands(companion);
        Optional<BlockPos> support = falling() ? hands.landingSupport() : Optional.empty();
        SelfCareFacts facts = new SelfCareFactsBuilder(companion).build(support);
        Optional<SelfCareDecision> decision = SelfCarePlanner.decide(facts);
        if (decision.isEmpty()) {
            return;
        }
        execute(decision.orElseThrow(), hands, support);
    }

    /** Stops a use in progress and gives the item back; called when the companion is dismissed or dies. */
    public void stop() {
        use.stop(companion);
    }

    /** The runner is ours only when nothing else has paused it and no other reflex owns the hands. */
    private boolean inControl() {
        TaskExecutionPort runner = companion.taskExecution();
        if (runner.paused() || runner.suspended()) {
            return false;
        }
        String tool = runner.currentToolName();
        return !FIRE_TOOL.equals(tool) && !RECOVER_TOOL.equals(tool);
    }

    /**
     * Cheap gate before the facts are built: when the body is whole, standing, dry, unpoisoned and the owner is fine,
     * nothing can be decided.
     */
    private boolean maySpend() {
        if (!companion.onGround() || companion.isInWater() || companion.isInLava()) {
            return true;
        }
        if (!companion.getActiveEffects().isEmpty()) {
            return true;
        }
        if (companion.getHealth() < companion.getMaxHealth()) {
            return true;
        }
        return companion
                .owner()
                .filter(player -> player.getHealth() > 0.0F && player.level() == companion.level())
                .filter(player -> player.getHealth() <= player.getMaxHealth() * SelfCarePlanner.OWNER_HURT)
                .isPresent();
    }

    private boolean falling() {
        return !companion.onGround()
                && !companion.isInWater()
                && !companion.isInLava()
                && companion.fallDistance >= SelfCarePlanner.FALL_HURT_BLOCKS;
    }

    private void execute(SelfCareDecision decision, SelfCareHands hands, Optional<BlockPos> support) {
        switch (decision.kind()) {
            case WATER_DROP -> drop(support, hands);
            case SPLASH_HEAL_OWNER -> splash(hands);
            default -> drink(decision);
        }
    }

    private void drop(Optional<BlockPos> support, SelfCareHands hands) {
        if (waterCooldown > 0 || support.isEmpty()) {
            return;
        }
        waterCooldown = WATER_DROP_COOLDOWN_TICKS;
        BlockPos cell = hands.pourWater(support.orElseThrow());
        if (cell != null) {
            pendingWater = cell;
            pendingTicks = 0;
        }
    }

    private void drink(SelfCareDecision decision) {
        if (consumeCooldown > 0 || companion.combatController().busy()) {
            return;
        }
        consumeCooldown = CONSUME_COOLDOWN_TICKS;
        ItemStack stack = SelfCareItems.take(companion.inventory(), decision.item());
        if (stack.isEmpty()) {
            return;
        }
        use.start(companion, stack);
    }

    private void splash(SelfCareHands hands) {
        if (consumeCooldown > 0) {
            return;
        }
        Optional<ServerPlayer> owner =
                companion.owner().filter(player -> player.getHealth() > 0.0F && player.level() == companion.level());
        if (owner.isEmpty()) {
            return;
        }
        consumeCooldown = CONSUME_COOLDOWN_TICKS;
        hands.throwSplashAt(owner.orElseThrow());
    }

    /**
     * Takes the landing source back as soon as the companion is standing in it. Past the window it leaves the source
     * to {@link RecoverWaterTask}, the way a cancelled build leaves its scaffold behind, so the water is not stranded
     * when the task resumes and walks off.
     */
    private void recoverWater() {
        if (pendingWater == null) {
            return;
        }
        SelfCareHands hands = new SelfCareHands(companion);
        if (!hands.holdsSource(pendingWater)) {
            pendingWater = null;
            return;
        }
        pendingTicks++;
        if (hands.recover(pendingWater)) {
            pendingWater = null;
            return;
        }
        if (pendingTicks < WATER_RECOVERY_TICKS) {
            return;
        }
        BlockPos source = pendingWater;
        pendingWater = null;
        TaskExecutionPort runner = companion.taskExecution();
        if (runner.paused() || runner.suspended()) {
            return;
        }
        runner.interrupt(new RecoverWaterTask(new Pos(source.getX(), source.getY(), source.getZ())), RECOVER_TOOL);
    }
}
