package dev.friedai.task.builtin;

import dev.friedai.crafting.CraftPlan;
import dev.friedai.crafting.CraftPlanner;
import dev.friedai.crafting.CraftStep;
import dev.friedai.crafting.CraftText;
import dev.friedai.crafting.Ingredient;
import dev.friedai.crafting.Recipe;
import dev.friedai.crafting.Station;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.Task;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.crafting.RecipeIndex;
import dev.friedai.task.crafting.Stations;
import dev.friedai.task.inventory.InventoryOps;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;

/**
 * Crafts one item, planning the intermediate crafts from the companion's inventory. Recipes that fit 2×2 are
 * crafted where the companion stands; steps that need a station are handed to the station branch.
 */
public final class CraftTask implements Task {
    /** Ticks between two crafts of the same step. */
    public static final int CRAFT_TICKS = 4;

    /** What to do with a station the companion placed itself. */
    public enum StationPolicy { AUTO, KEEP, TAKE }

    private final String item;
    private final int count;
    private final StationPolicy policy;

    private RecipeIndex index;
    private CraftPlan plan;
    private String failure;
    private int stepIndex;
    private int stepTicks;
    private int craftsDone;
    private String summary = "";

    public CraftTask(String item, int count, StationPolicy policy) {
        if (item == null || item.isBlank()) {
            throw new IllegalArgumentException("craft target must not be empty");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("craft count must be positive: " + count);
        }
        Identifier id = Identifier.tryParse(item.strip());
        this.item = id == null ? item.strip() : id.toString();
        this.count = count;
        this.policy = policy == null ? StationPolicy.AUTO : policy;
    }

    @Override
    public String type() {
        return "craft_item";
    }

    @Override
    public void start(TaskContext c) {
        try {
            index = RecipeIndex.of(c.level());
            if (index.producing(item).isEmpty()) {
                failure = item + " has no crafting, stonecutter or smithing recipe";
                return;
            }
            plan = CraftPlanner.plan(
                    item,
                    count,
                    inventoryMap(c.companion()),
                    index,
                    Stations.available(c.level(), c.companion()));
            if (!plan.ok()) {
                failure = "can't craft " + count + " " + item + "; " + CraftText.missingText(plan.missing());
            }
        } catch (RuntimeException e) {
            failure = "can't craft " + count + " " + item;
        }
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (failure != null) {
            summary = failure;
            return TaskStatus.FAILED;
        }
        if (plan == null) {
            summary = "can't craft " + count + " " + item;
            return TaskStatus.FAILED;
        }
        if (stepIndex >= plan.steps().size()) {
            return finish();
        }
        CraftStep step = plan.steps().get(stepIndex);
        if (step.recipe().station() != Station.INVENTORY) {
            return tickStation(c, step);
        }
        stepTicks++;
        if (stepTicks < CRAFT_TICKS) {
            return TaskStatus.RUNNING;
        }
        stepTicks = 0;
        swing(c.companion());
        if (!craftOnce(c, step)) {
            summary = "the inventory changed while crafting: " + remainingMissing(c);
            return TaskStatus.FAILED;
        }
        craftsDone++;
        if (craftsDone >= step.times()) {
            stepIndex++;
            craftsDone = 0;
            if (stepIndex >= plan.steps().size()) {
                return finish();
            }
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        String base = "crafting " + count + " " + item;
        if (plan != null && stepIndex < plan.steps().size()) {
            CraftStep step = plan.steps().get(stepIndex);
            return base + ": step " + (stepIndex + 1) + "/" + plan.steps().size() + " ("
                    + step.recipe().output() + ")";
        }
        return base;
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    /**
     * A step that needs a station: placing or approaching a crafting table, stonecutter or smithing table is the
     * station branch's job, so this path never touches the world.
     */
    private TaskStatus tickStation(TaskContext c, CraftStep step) {
        summary = "can't craft " + count + " " + item + " " + step.recipe().station().phrase();
        return TaskStatus.FAILED;
    }

    private TaskStatus finish() {
        if (plan.steps().isEmpty()) {
            summary = "crafted 0 " + item;
            return TaskStatus.SUCCESS;
        }
        CraftStep last = plan.steps().getLast();
        StringBuilder text = new StringBuilder("crafted ")
                .append(produced(last))
                .append(' ')
                .append(item);
        if (plan.steps().size() > 1) {
            StringJoiner also = new StringJoiner(", ");
            for (CraftStep step : plan.steps().subList(0, plan.steps().size() - 1)) {
                also.add(produced(step) + " " + step.recipe().output());
            }
            text.append(" (also crafted ").append(also).append(')');
        }
        summary = text.toString();
        return TaskStatus.SUCCESS;
    }

    private static int produced(CraftStep step) {
        return step.recipe().outputCount() * step.times();
    }

    /**
     * Runs one craft of the step from the current inventory and main hand. Returns false when the ingredients
     * are no longer there or the recipe cannot be replayed, leaving the inventory untouched.
     */
    private boolean craftOnce(TaskContext c, CraftStep step) {
        var holder = index.holder(step.recipe().id());
        if (holder.isEmpty() || !(holder.get().value() instanceof CraftingRecipe recipe)) {
            return false;
        }
        CompanionEntity companion = c.companion();
        SimpleContainer inventory = companion.inventory();

        StackedItemContents contents = new StackedItemContents();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            contents.accountStack(inventory.getItem(slot));
        }
        contents.accountStack(companion.getMainHandItem());
        List<Holder<Item>> picked = new ArrayList<>();
        if (!contents.canCraft(recipe, picked::add)) {
            return false;
        }

        PlacementInfo placement = recipe.placementInfo();
        List<net.minecraft.world.item.crafting.Ingredient> ingredients = placement.ingredients();
        IntList slots = placement.slotsToIngredientIndex();
        int width;
        int height;
        if (recipe instanceof ShapedRecipe shaped) {
            width = shaped.getWidth();
            height = shaped.getHeight();
        } else {
            width = ingredients.size();
            height = 1;
        }
        if (picked.size() != ingredients.size() || slots.size() != width * height) {
            return false;
        }

        List<ItemStack> grid = new ArrayList<>(Collections.nCopies(width * height, ItemStack.EMPTY));
        int[] taken = new int[inventory.getContainerSize() + 1];
        for (int slot = 0; slot < slots.size(); slot++) {
            int ingredientIndex = slots.getInt(slot);
            if (ingredientIndex < 0) {
                continue;
            }
            ItemStack stack = takeConcrete(
                    companion, ingredients.get(ingredientIndex), picked.get(ingredientIndex), taken);
            if (stack.isEmpty()) {
                return false;
            }
            grid.set(slot, stack);
        }

        CraftingInput input = CraftingInput.of(width, height, grid);
        if (!recipe.matches(input, c.level())) {
            return false;
        }
        ItemStack result = recipe.assemble(input);
        NonNullList<ItemStack> remaining = recipe.getRemainingItems(input);
        consume(companion, taken);
        InventoryOps.insertOrDrop(companion, result);
        for (ItemStack stack : remaining) {
            if (!stack.isEmpty()) {
                InventoryOps.insertOrDrop(companion, stack);
            }
        }
        return true;
    }

    /**
     * The first inventory slot, then the main hand, holding an item of the type the recipe picker chose for the
     * slot and not already reserved by an earlier slot of the same craft.
     */
    private static ItemStack takeConcrete(
            CompanionEntity companion,
            net.minecraft.world.item.crafting.Ingredient ingredient,
            Holder<Item> picked,
            int[] taken) {
        SimpleContainer inventory = companion.inventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getCount() <= taken[slot] || stack.getItem() != picked.value() || !ingredient.test(stack)) {
                continue;
            }
            taken[slot]++;
            return stack.copyWithCount(1);
        }
        int handSlot = inventory.getContainerSize();
        ItemStack hand = companion.getMainHandItem();
        if (hand.getCount() > taken[handSlot]
                && hand.getItem() == picked.value()
                && ingredient.test(hand)) {
            taken[handSlot]++;
            return hand.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    private static void consume(CompanionEntity companion, int[] taken) {
        SimpleContainer inventory = companion.inventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (taken[slot] == 0) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            stack.shrink(taken[slot]);
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
        int handTaken = taken[inventory.getContainerSize()];
        if (handTaken > 0) {
            ItemStack hand = companion.getMainHandItem();
            hand.shrink(handTaken);
            if (hand.isEmpty()) {
                companion.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }
        inventory.setChanged();
    }

    /** What the rest of the plan is still short of after the inventory changed under the task. */
    private String remainingMissing(TaskContext c) {
        int producedTarget = 0;
        CraftStep last = plan.steps().getLast();
        if (stepIndex == plan.steps().size() - 1) {
            producedTarget = craftsDone * last.recipe().outputCount();
        }
        int remaining = Math.max(1, count - producedTarget);
        try {
            CraftPlan rest = CraftPlanner.plan(
                    item,
                    remaining,
                    inventoryMap(c.companion()),
                    index,
                    Stations.available(c.level(), c.companion()));
            if (!rest.missing().isEmpty()) {
                return CraftText.missingText(rest.missing());
            }
        } catch (RuntimeException e) {
            // Fall through to the failed step's own ingredients.
        }
        Recipe recipe = plan.steps().get(stepIndex).recipe();
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (Ingredient ingredient : recipe.ingredients()) {
            missing.merge(ingredient.label(), 1, Integer::sum);
        }
        return CraftText.missingText(missing);
    }

    private static Map<String, Integer> inventoryMap(CompanionEntity companion) {
        Map<String, Integer> items = new LinkedHashMap<>();
        SimpleContainer inventory = companion.inventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            account(items, inventory.getItem(slot));
        }
        account(items, companion.getMainHandItem());
        return items;
    }

    private static void account(Map<String, Integer> items, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
    }

    private static void swing(CompanionEntity companion) {
        companion.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
    }
}
