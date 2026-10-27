package dev.friendai.task.builtin;

import dev.friendai.crafting.Recipe;
import dev.friendai.crafting.Station;
import dev.friendai.debug.DebugTarget;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.crafting.RecipeIndex;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Cooks the raw food the companion carries, using a smoker, campfire or furnace nearby. */
public final class CookFoodTask implements Task {
    private final int count;
    private final boolean forceWait;
    private final CraftTask.StationPolicy policy;
    private final int waitLimitTicks;

    @Nullable
    private SmeltTask current;
    private String failure;
    private String summary = "";

    public CookFoodTask(int count, boolean forceWait, CraftTask.StationPolicy policy, int waitLimitTicks) {
        if (count <= 0) {
            throw new IllegalArgumentException("cook count must be positive: " + count);
        }
        this.count = count;
        this.forceWait = forceWait;
        this.policy = policy == null ? CraftTask.StationPolicy.AUTO : policy;
        this.waitLimitTicks = waitLimitTicks;
    }

    @Override
    public String type() {
        return "cook_food";
    }

    @Override
    public List<DebugTarget> debugTargets() {
        return current == null ? List.of() : current.debugTargets();
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public void start(TaskContext c) {
        try {
            RecipeIndex index = RecipeIndex.of(c.level());
            Set<String> ids = rawFood(c.companion(), index);
            if (ids.isEmpty()) {
                failure = "I have no raw food";
                return;
            }
            int total = 0;
            SimpleContainer inventory = c.companion().inventory();
            for (int slot = 0; slot < inventory.getContainerSize() && total < count; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && ids.contains(idOf(stack))) {
                    total += Math.min(stack.getCount(), count - total);
                }
            }
            current = new SmeltTask(new SetMatcher(ids), total, null, null, forceWait, policy, waitLimitTicks);
            current.start(c);
        } catch (RuntimeException e) {
            failure = "I have no raw food";
        }
    }

    private static Set<String> rawFood(CompanionEntity companion, RecipeIndex index) {
        Set<String> ids = new LinkedHashSet<>();
        SimpleContainer inventory = companion.inventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || ids.contains(idOf(stack))) {
                continue;
            }
            for (Recipe recipe : index.cookingFrom(idOf(stack))) {
                if (recipe.station() == Station.SMOKER || recipe.station() == Station.CAMPFIRE) {
                    ids.add(idOf(stack));
                    break;
                }
            }
        }
        return ids;
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (failure != null) {
            summary = failure;
            return TaskStatus.FAILED;
        }
        if (current == null) {
            return TaskStatus.FAILED;
        }
        TaskStatus status = current.tick(c);
        if (status == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        summary = current.resultSummary();
        current = null;
        return status;
    }

    @Override
    public void stop(TaskContext c) {
        if (current != null) {
            current.stop(c);
            current = null;
        }
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return current == null ? "cooking " + count + " raw food" : current.describe();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** Matches any of the selected raw-food item ids, with a label that reads well in summaries. */
    private record SetMatcher(Set<String> ids) implements ItemMatcher {
        @Override
        public boolean test(ItemStack stack) {
            return !stack.isEmpty() && ids.contains(idOf(stack));
        }

        @Override
        public String label() {
            return ids.size() == 1 ? ids.iterator().next() : "raw food";
        }
    }
}
