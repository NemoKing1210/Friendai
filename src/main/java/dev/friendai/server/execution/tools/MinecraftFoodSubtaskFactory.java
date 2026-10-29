package dev.friendai.server.execution.tools;

import dev.friendai.features.animals.minecraft.task.FishTask;
import dev.friendai.features.combat.minecraft.task.HuntTask;
import dev.friendai.features.cooking.minecraft.task.CookFoodTask;
import dev.friendai.features.crafting.StationPolicy;
import dev.friendai.features.crafting.minecraft.task.CraftTask;
import dev.friendai.features.farming.minecraft.task.FarmTask;
import dev.friendai.features.survival.minecraft.FoodSubtaskFactory;
import dev.friendai.task.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;

/** Builds the cross-feature tasks used by survival's food-gathering workflow. */
public final class MinecraftFoodSubtaskFactory implements FoodSubtaskFactory {
    @Override
    public Task cook(int count) {
        return new CookFoodTask(count, true, StationPolicy.AUTO, 1_200);
    }

    @Override
    public Task bakeBread(int loaves) {
        return new CraftTask("minecraft:bread", loaves, StationPolicy.AUTO);
    }

    @Override
    public Task farm(BlockPos center, int radius) {
        return new FarmTask(center, radius, null, false);
    }

    @Override
    public Task hunt(EntityType<?> type, String label, int count, int radius) {
        return new HuntTask(candidate -> candidate == type, label, count, radius);
    }

    @Override
    public Task fish(int count) {
        return new FishTask(count);
    }
}
