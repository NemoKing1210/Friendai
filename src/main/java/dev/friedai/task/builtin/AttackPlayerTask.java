package dev.friedai.task.builtin;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.Task;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.combat.CombatController;
import dev.friedai.task.combat.Threats;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Attacks one player until they are defeated, gone, out of reach or a minute passes. */
public final class AttackPlayerTask implements Task {
    private static final int LIMIT_TICKS = 60 * 20;
    private static final double MAX_DISTANCE = 32.0;

    private final UUID targetId;
    private final String name;
    private int ticks;
    private String summary = "";

    public AttackPlayerTask(UUID targetId, String name) {
        this.targetId = targetId;
        this.name = name;
    }

    @Override
    public String type() {
        return "attack_player";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        if (++ticks > LIMIT_TICKS) {
            summary = name + " got away";
            return TaskStatus.FAILED;
        }
        if (!(c.companion().level() instanceof ServerLevel level)) {
            summary = name + " left";
            return TaskStatus.FAILED;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(targetId);
        if (player == null || player.isRemoved() || player.level() != level) {
            summary = name + " left";
            return TaskStatus.FAILED;
        }
        if (player.isDeadOrDying()) {
            summary = name + " was defeated";
            return TaskStatus.SUCCESS;
        }
        if (c.companion().distanceTo(player) > MAX_DISTANCE) {
            summary = name + " got away";
            return TaskStatus.FAILED;
        }
        if (!Threats.mayFightPlayer(c.companion(), player)) {
            summary = level.isPvpAllowed() ? "I can't hurt " + name + " here" : "PvP is off here";
            return TaskStatus.FAILED;
        }
        CombatController controller = c.companion().combatController();
        if (controller.state() != CombatController.State.FIGHTING) {
            controller.fight(player);
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().combatController().stopFight();
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return "attacking " + name;
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
