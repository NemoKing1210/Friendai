package dev.friendai.task;

import dev.friendai.entity.CompanionEntity;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public record TaskContext(CompanionEntity companion, ServerLevel level, TaskRunner runner) {
    /** The context of a task it ticks itself, for tests and for tasks that run others. */
    public TaskContext(CompanionEntity companion, ServerLevel level) {
        this(companion, level, companion.taskRunner());
    }

    public Optional<ServerPlayer> owner() {
        return companion.owner();
    }
}
