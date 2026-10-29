package dev.friendai.features.survival.minecraft;

import dev.friendai.task.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;

/** Application composition point for optional work performed by a food-gathering task. */
public interface FoodSubtaskFactory {
    Task cook(int count);

    Task bakeBread(int loaves);

    Task farm(BlockPos center, int radius);

    Task hunt(EntityType<?> type, String label, int count, int radius);

    Task fish(int count);
}
