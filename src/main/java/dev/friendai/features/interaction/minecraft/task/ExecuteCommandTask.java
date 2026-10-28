package dev.friendai.features.interaction.minecraft.task;

import dev.friendai.command.CompanionCommandExecutor;
import dev.friendai.features.creative.CreativeAction;
import dev.friendai.features.creative.CreativeCommandPolicy;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import net.minecraft.server.level.ServerPlayer;

/** One server-thread command dispatch under the live owner's unmodified permission set. */
public final class ExecuteCommandTask implements Task {
    private final String command;
    private String summary = "";

    public ExecuteCommandTask(String command) {
        this.command = command;
    }

    @Override
    public String type() {
        return "execute_command";
    }

    @Override
    public void start(TaskContext context) {
        summary = "";
    }

    @Override
    public TaskStatus tick(TaskContext context) {
        if (!context.canUseCreativeAction(CreativeAction.EXECUTE_COMMAND)) {
            summary = "command denied: creative command permission is no longer active";
            return TaskStatus.FAILED;
        }
        ServerPlayer owner = context.owner().orElse(null);
        if (owner == null) {
            summary = "command denied: the owner must be online";
            return TaskStatus.FAILED;
        }
        CreativeCommandPolicy.Decision decision =
                CreativeCommandPolicy.evaluate(command, owner.getName().getString());
        if (!decision.allowed()) {
            summary = "command denied: " + decision.reason();
            return TaskStatus.FAILED;
        }
        CompanionCommandExecutor.Result result = CompanionCommandExecutor.execute(
                context.level().getServer(), context.companion(), owner, decision.command());
        summary = (result.success() ? "command succeeded: " : "command failed: ") + result.text();
        return result.success() ? TaskStatus.SUCCESS : TaskStatus.FAILED;
    }

    @Override
    public void stop(TaskContext context) {}

    @Override
    public String describe() {
        return "running an allowlisted gameplay command";
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
