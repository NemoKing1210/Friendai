package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.combat.CombatController;
import dev.friendai.task.combat.Threats;
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
            return finish(c, name + " got away", TaskStatus.FAILED);
        }
        if (!(c.companion().level() instanceof ServerLevel level)) {
            return finish(c, name + " left", TaskStatus.FAILED);
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(targetId);
        if (player == null || player.isRemoved() || player.level() != level) {
            return finish(c, name + " left", TaskStatus.FAILED);
        }
        if (player.isDeadOrDying()) {
            return finish(c, name + " was defeated", TaskStatus.SUCCESS);
        }
        if (c.companion().distanceTo(player) > MAX_DISTANCE) {
            return finish(c, name + " got away", TaskStatus.FAILED);
        }
        if (!Threats.mayFightPlayer(c.companion(), player)) {
            return finish(
                    c,
                    level.isPvpAllowed() ? "I can't hurt " + name + " here" : "PvP is off here",
                    TaskStatus.FAILED);
        }
        CombatController controller = c.companion().combatController();
        if (controller.state() != CombatController.State.FIGHTING) {
            controller.fight(player);
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus finish(TaskContext c, String text, TaskStatus status) {
        c.companion().combatController().stopFight();
        summary = text;
        return status;
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
