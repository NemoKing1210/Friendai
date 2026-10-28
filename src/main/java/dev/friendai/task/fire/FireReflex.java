package dev.friendai.task.fire;

import dev.friendai.core.Components;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.fire.FireTarget;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.builtin.ExtinguishFireTask;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/**
 * Being on fire is an emergency, like combat: before the task runner ticks, a burning companion drops its own fire
 * and gets back to its job. The owner catching fire is handled the same way when they are within reach — the chat
 * line is said from where the companion stands, never after a walk across the world.
 *
 * <p>The runner's own interrupt/freeze takes care of the work in progress: a job-backed task is frozen and resumed
 * when the fire task finishes, exactly as combat and the hunger watcher do it.
 */
public final class FireReflex {
    /** How long after a fire task the reflex keeps quiet, so a failed attempt cannot loop. */
    public static final int COOLDOWN_TICKS = 100;
    /** The tool name the running task reports, so the reflex does not interrupt its own fire task. */
    public static final String TOOL = "extinguish_fire";

    private final CompanionEntity companion;
    private int cooldown;
    private boolean saidSelf;
    private boolean saidOwner;

    public FireReflex(CompanionEntity companion) {
        this.companion = companion;
    }

    public void tick() {
        if (cooldown > 0) {
            cooldown--;
        }
        if (!companion.isAlive()) {
            return;
        }
        boolean selfBurning = companion.isOnFire();
        if (!selfBurning) {
            saidSelf = false;
        }
        Optional<ServerPlayer> owner = companion.owner();
        boolean ownerBurning = owner.filter(player -> player.level() == companion.level() && player.isOnFire())
                .isPresent();
        if (!ownerBurning) {
            saidOwner = false;
        }
        if (!selfBurning && !ownerBurning) {
            return;
        }
        ExecutionCoordinator runner = companion.executionCoordinator();
        if (runner.paused() || runner.currentToolName().equals(TOOL) || cooldown > 0) {
            return;
        }
        if (selfBurning) {
            if (!saidSelf) {
                saidSelf = true;
                say("friendai.fire.self");
            }
            runner.interrupt(new ExtinguishFireTask(new FireTarget.Self(), FireScan.REACH, true), TOOL);
            cooldown = COOLDOWN_TICKS;
            return;
        }
        ServerPlayer player = owner.orElseThrow();
        double distance = player.distanceToSqr(companion);
        if (distance > (double) FireScan.OWNER_REACH * FireScan.OWNER_REACH) {
            if (!saidOwner) {
                saidOwner = true;
                say("friendai.fire.owner_out_of_reach");
            }
            return;
        }
        if (!saidOwner) {
            saidOwner = true;
            say("friendai.fire.owner");
        }
        runner.interrupt(new ExtinguishFireTask(new FireTarget.Owner(), FireScan.OWNER_REACH, true), TOOL);
        cooldown = COOLDOWN_TICKS;
    }

    /** Says one short line to the owner; the reflex never walks over to talk. */
    private void say(String key) {
        companion.owner().ifPresent(player -> player.sendSystemMessage(Components.of(UserMessage.of(key)), true));
    }
}
