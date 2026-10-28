package dev.friendai.task;

import dev.friendai.core.CreativeModeService;
import dev.friendai.creative.CreativeAction;
import dev.friendai.creative.CreativeActionContext;
import dev.friendai.entity.CompanionEntity;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public record TaskContext(
        CompanionEntity companion, ServerLevel level, TaskRunner runner, CreativeActionContext creativeActionContext) {
    public TaskContext(CompanionEntity companion, ServerLevel level, TaskRunner runner) {
        this(companion, level, runner, null);
    }

    /** The context of a task it ticks itself, for tests and for tasks that run others. */
    public TaskContext(CompanionEntity companion, ServerLevel level) {
        this(companion, level, companion.taskRunner(), null);
    }

    public Optional<ServerPlayer> owner() {
        return companion.owner();
    }

    public boolean canUseCreativeAction(CreativeAction action) {
        return CreativeModeService.isActionAuthorized(companion, creativeActionContext, action);
    }

    /** The running task belongs to free time, which follows stricter rules than an owner order. */
    public boolean autonomous() {
        return runner != null && runner.autonomousActive();
    }
}
