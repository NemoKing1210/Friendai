package dev.friendai.server.execution.tools;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.acquisition.minecraft.task.SearchResourceTask;
import dev.friendai.task.BlockSearchTaskFactory;
import dev.friendai.task.Task;
import java.util.Objects;
import java.util.UUID;

/**
 * Server composition of {@link BlockSearchTaskFactory}: the acquisition resource search, run on behalf of the
 * companion's owner and scoped to the local strategy. The search keeps its own configured radius and tick budget;
 * the caller bounds how long it keeps ticking the returned task.
 */
public final class MinecraftBlockSearchFactory implements BlockSearchTaskFactory {
    private final MinecraftObtainSubtaskFactory subtasks;

    public MinecraftBlockSearchFactory(MinecraftObtainSubtaskFactory subtasks) {
        this.subtasks = Objects.requireNonNull(subtasks, "subtasks");
    }

    @Override
    public Task create(CompanionEntity companion, String blockOrTag, int radius, int maxTicks) {
        if (companion == null || blockOrTag == null || blockOrTag.isBlank() || radius <= 0 || maxTicks <= 0) {
            return null;
        }
        UUID owner = companion.ownerUuid();
        if (owner == null) {
            return null;
        }
        Caller caller = new Caller(owner, "", AccessLevel.OWNER);
        return new SearchResourceTask(blockOrTag, "auto", "local", "", caller, subtasks);
    }
}
