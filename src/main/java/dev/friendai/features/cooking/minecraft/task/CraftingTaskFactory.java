package dev.friendai.features.cooking.minecraft.task;

import dev.friendai.features.crafting.StationPolicy;
import dev.friendai.task.Task;

/** Creates a crafting task for cooking without coupling cooking to the crafting task implementation. */
@FunctionalInterface
public interface CraftingTaskFactory {
    CraftingTaskFactory UNAVAILABLE = (item, count, policy) -> {
        throw new IllegalStateException("crafting task factory is unavailable");
    };

    Task create(String item, int count, StationPolicy policy);
}
