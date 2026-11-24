package dev.friendai.features.acquisition.minecraft.task;

import dev.friendai.task.Task;
import java.util.Set;

/** Creates the bounded exploration task used by acquisition searches without owning its implementation. */
@FunctionalInterface
public interface ExploreTaskFactory {
    ExploreTaskFactory UNAVAILABLE = (blocks, down, depthY, radius, maxBlocks, maxTicks) -> {
        throw new IllegalStateException("exploration task factory is unavailable");
    };

    /**
     * Explores for any block in {@code blocks}. A resource family arrives whole — both an ore and its deepslate
     * variant — so the child recognises the first variant it actually reaches instead of digging for one id.
     */
    MiningTask create(Set<String> blocks, boolean down, int depthY, int radius, int maxBlocks, int maxTicks);

    /** A search for one block id. */
    default MiningTask create(String block, boolean down, int depthY, int radius, int maxBlocks, int maxTicks) {
        return create(Set.of(block), down, depthY, radius, maxBlocks, maxTicks);
    }

    interface MiningTask extends Task {
        int excavationBlocks();

        default java.util.List<dev.friendai.foundation.spatial.Pos> returnRoute() {
            return java.util.List.of();
        }
    }
}
