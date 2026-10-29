package dev.friendai.features.acquisition.minecraft.task;

import dev.friendai.task.Task;

/** Creates the bounded exploration task used by acquisition searches without owning its implementation. */
@FunctionalInterface
public interface ExploreTaskFactory {
    ExploreTaskFactory UNAVAILABLE = (block, down, depthY, radius, maxBlocks, maxTicks) -> {
        throw new IllegalStateException("exploration task factory is unavailable");
    };

    MiningTask create(String block, boolean down, int depthY, int radius, int maxBlocks, int maxTicks);

    interface MiningTask extends Task {
        int excavationBlocks();
    }
}
