package dev.friendai.server.execution.tools;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.acquisition.minecraft.task.SearchResourceTask;
import dev.friendai.task.BlockSearchTaskFactory;
import dev.friendai.task.Task;
import java.util.Objects;

/**
 * Server composition of {@link BlockSearchTaskFactory}: the acquisition resource search, run for the caller whose
 * mining job needs it and scoped to the local strategy. The search keeps its own configured radius and tick budget;
 * the caller bounds how long it keeps ticking the returned task.
 */
public final class MinecraftBlockSearchFactory implements BlockSearchTaskFactory {
    private final MinecraftObtainSubtaskFactory subtasks;

    public MinecraftBlockSearchFactory(MinecraftObtainSubtaskFactory subtasks) {
        this.subtasks = Objects.requireNonNull(subtasks, "subtasks");
    }

    @Override
    public Task create(CompanionEntity companion, Caller caller, String blockOrTag, int radius, int maxTicks) {
        if (companion == null || companion.ownerUuid() == null || !accepts(caller, blockOrTag, radius, maxTicks)) {
            return null;
        }
        return new SearchResourceTask(blockOrTag, "auto", "local", "", caller, subtasks);
    }

    /**
     * Whether a search may be created for this call. A delegated search never runs above the authority its caller
     * already holds: a guest cannot borrow a search from a mining job. Package-private so a headless test can pin the
     * refusal without a world.
     */
    static boolean accepts(Caller caller, String blockOrTag, int radius, int maxTicks) {
        return caller != null
                && blockOrTag != null
                && !blockOrTag.isBlank()
                && radius > 0
                && maxTicks > 0
                && caller.access().ordinal() >= AccessLevel.TRUSTED.ordinal();
    }
}
