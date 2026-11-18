package dev.friendai.features.brewing.minecraft.task;

import dev.friendai.features.brewing.BrewPlanner;
import dev.friendai.features.brewing.BrewRecipe;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.MenuBlockTask;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.jetbrains.annotations.Nullable;

/** Brews one batch through the vanilla stand and verifies each operation before moving its output. */
public final class BrewTask extends MenuBlockTask {
    private static final int BOTTLE_SLOTS = BrewRecipe.BOTTLES_PER_OPERATION;
    private static final int INGREDIENT_SLOT = 3;
    private static final int FUEL_SLOT = 4;
    private static final int WAIT_MARGIN = 60;
    private static final int CHECK_INTERVAL = 5;

    private record Step(ResourceKey<Potion> input, ItemMatcher ingredient, ResourceKey<Potion> output) {}

    private final String target;
    private final int bottles;
    private final List<Step> steps;
    private final @Nullable BlockPos requestedStand;
    private @Nullable BlockPos batchStand;
    private @Nullable Identifier batchDimension;
    private @Nullable BrewingStandBlockEntity observedStand;
    private int stepIndex;
    private boolean staged;
    private String addedFuel;
    private int fuelBefore;
    private int waited;
    private int waitLimit = BrewRecipe.OPERATION_TICKS + WAIT_MARGIN;

    public BrewTask(String target, int bottles, @Nullable BlockPos stand) {
        this(target, bottles, stand, null, 0, false, "", 0);
    }

    BrewTask(
            String target,
            int bottles,
            @Nullable BlockPos stand,
            @Nullable Identifier batchDimension,
            int stepIndex,
            boolean staged,
            String addedFuel,
            int fuelBefore) {
        super(Blocks.BREWING_STAND, "brewing stand", stand);
        this.target = target == null ? "" : target.strip().toLowerCase(java.util.Locale.ROOT);
        if (bottles < 1 || bottles > BOTTLE_SLOTS) {
            throw new IllegalArgumentException("bottles must be 1 to 3");
        }
        this.bottles = bottles;
        this.steps = BrewPlanner.plan(this.target)
                .map(recipe -> recipe.steps().stream()
                        .map(step -> new Step(
                                potionKey(step.fromPotion()),
                                ItemMatcher.parse(step.ingredient()).orElseThrow(),
                                potionKey(step.toPotion())))
                        .toList())
                .orElseGet(List::of);
        this.requestedStand = stand;
        this.batchStand = batchDimension == null ? null : stand;
        this.batchDimension = batchDimension;
        this.stepIndex = stepIndex;
        this.staged = staged;
        this.addedFuel = addedFuel;
        this.fuelBefore = fuelBefore;
    }

    @Override
    public String type() {
        return "brew_potion";
    }

    @Override
    public String describe() {
        return "brewing " + bottles + " " + target;
    }

    @Override
    public @Nullable String saveData() {
        if (stepIndex >= steps.size()) {
            return null;
        }
        BlockPos pos = batchStand == null ? requestedStand : batchStand;
        return target + "|" + bottles + "|" + stepIndex + "|" + (pos == null ? "" : pos.asLong()) + "|"
                + (batchDimension == null ? "" : batchDimension) + "|" + staged + "|" + addedFuel + "|" + fuelBefore;
    }

    @Override
    public long progressStamp() {
        return stepIndex * 2L + (staged ? 1 : 0);
    }

    @Override
    public int waitingTicks() {
        return staged ? waited : -1;
    }

    @Override
    public int waitingLimitTicks() {
        return waitLimit;
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (batchDimension != null
                && !batchDimension.equals(c.level().dimension().identifier())) {
            return fail("the brewing batch is in another dimension");
        }
        return super.tick(c);
    }

    @Override
    protected TaskStatus operate(TaskContext c, BlockPos pos) {
        if (steps.isEmpty()) {
            return fail("unsupported potion: " + target);
        }
        if (stepIndex >= steps.size()) {
            return TaskStatus.SUCCESS;
        }
        if (staged && waited++ % CHECK_INTERVAL != 0) {
            return TaskStatus.RUNNING;
        }
        if (!(c.level().getBlockEntity(pos) instanceof BrewingStandBlockEntity stand)
                || !(c.companion().hands().openMenu(pos) instanceof BrewingStandMenu menu)) {
            return fail("the brewing stand at " + coords(pos) + " would not open");
        }
        if (batchStand == null) {
            return startBatch(c, pos, stand, menu);
        }
        if (observedStand != null && observedStand != stand) {
            return fail("the brewing stand at " + coords(pos) + " was replaced");
        }
        return continueBatch(c, stand, menu);
    }

    private TaskStatus startBatch(TaskContext c, BlockPos pos, BrewingStandBlockEntity stand, BrewingStandMenu menu) {
        if (!stand.isEmpty() || menu.getBrewingTicks() > 0) {
            return fail("the brewing stand at " + coords(pos) + " is occupied");
        }
        Container inventory = c.companion().inventory();
        ResourceKey<Potion> water = steps.getFirst().input();
        if (countPotions(inventory, water) < bottles) {
            return fail("I do not have " + bottles + " water bottle(s)");
        }
        Map<String, Integer> requirements = new LinkedHashMap<>();
        for (Step step : steps) {
            requirements.merge(step.ingredient().label(), 1, Integer::sum);
        }
        for (var requirement : requirements.entrySet()) {
            ItemMatcher ingredient = ItemMatcher.parse(requirement.getKey()).orElseThrow();
            if (InventoryOps.count(inventory, ingredient) < requirement.getValue()) {
                return fail("I do not have " + label(ingredient.label()));
            }
        }
        if (menu.getFuel() < steps.size() && fuelSlot(inventory) < 0) {
            return fail("I do not have brewing fuel in addition to the ingredients");
        }

        int taken = 0;
        for (int slot = 0; slot < inventory.getContainerSize() && taken < bottles; slot++) {
            while (taken < bottles && isPotion(inventory.getItem(slot), water)) {
                menu.getSlot(taken++).set(inventory.removeItem(slot, 1));
            }
        }
        batchStand = pos;
        batchDimension = c.level().dimension().identifier();
        observedStand = stand;
        return stage(inventory, menu);
    }

    private TaskStatus continueBatch(TaskContext c, BrewingStandBlockEntity stand, BrewingStandMenu menu) {
        Step step = steps.get(stepIndex);
        if (staged
                && menu.getBrewingTicks() == 0
                && stand.getItem(INGREDIENT_SLOT).isEmpty()
                && standMatches(stand, step.output())) {
            if (!spentFuelUse(stand, menu)) {
                return fail(
                        "the bottles changed without a verified brewing operation at " + coords(stand.getBlockPos()));
            }
            observedStand = stand;
            stepIndex++;
            staged = false;
            addedFuel = "";
            if (stepIndex == steps.size()) {
                return finish(c, stand);
            }
            return stage(c.companion().inventory(), menu);
        }
        ItemStack ingredient = stand.getItem(INGREDIENT_SLOT);
        if (!standMatches(stand, step.input())
                || (staged
                        ? ingredient.getCount() != 1 || !step.ingredient().test(ingredient)
                        : !ingredient.isEmpty())) {
            return fail("the brewing batch at " + coords(stand.getBlockPos()) + " no longer matches its staged inputs");
        }
        observedStand = stand;
        if (!staged) {
            return stage(c.companion().inventory(), menu);
        }
        waitLimit = Math.max(waitLimit, menu.getTotalBrewingTicks() + WAIT_MARGIN);
        if (waited >= waitLimit) {
            return fail("the stand did not finish brewing "
                    + label(step.output().identifier().toString()));
        }
        return TaskStatus.RUNNING;
    }

    private boolean spentFuelUse(BrewingStandBlockEntity stand, BrewingStandMenu menu) {
        if (fuelBefore > 0) {
            return menu.getFuel() == fuelBefore - 1;
        }
        return menu.getTotalFuel() > 0
                && menu.getFuel() == menu.getTotalFuel() - 1
                && !isAddedFuel(stand.getItem(FUEL_SLOT));
    }

    private TaskStatus stage(Container inventory, BrewingStandMenu menu) {
        Step step = steps.get(stepIndex);
        if (InventoryOps.count(inventory, step.ingredient()) == 0) {
            return fail("I do not have " + label(step.ingredient().label()));
        }
        if (!menu.getSlot(FUEL_SLOT).getItem().isEmpty()) {
            return fail("the brewing stand's fuel slot changed");
        }
        fuelBefore = menu.getFuel();
        if (fuelBefore <= 0) {
            int slot = fuelSlot(inventory);
            if (slot < 0) {
                return fail("I do not have brewing fuel in addition to the ingredients");
            }
            ItemStack fuel = inventory.removeItem(slot, 1);
            addedFuel = BuiltInRegistries.ITEM.getKey(fuel.getItem()).toString();
            menu.getSlot(FUEL_SLOT).set(fuel);
        }
        menu.getSlot(INGREDIENT_SLOT)
                .set(InventoryOps.remove(inventory, step.ingredient(), 1).getFirst());
        staged = true;
        waited = 0;
        waitLimit = BrewRecipe.OPERATION_TICKS + WAIT_MARGIN;
        return TaskStatus.RUNNING;
    }

    /** Selects an item with the fuel component only when enough of that item remains for its recipe uses. */
    private int fuelSlot(Container inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack fuel = inventory.getItem(slot);
            if (!fuel.has(DataComponents.BREWING_FUEL)) {
                continue;
            }
            int reserved = 0;
            ItemMatcher matchingIngredient = null;
            for (int step = stepIndex; step < steps.size(); step++) {
                ItemMatcher ingredient = steps.get(step).ingredient();
                if (ingredient.test(fuel)) {
                    reserved++;
                    matchingIngredient = ingredient;
                }
            }
            if (matchingIngredient == null || InventoryOps.count(inventory, matchingIngredient) > reserved) {
                return slot;
            }
        }
        return -1;
    }

    private TaskStatus finish(TaskContext c, BrewingStandBlockEntity stand) {
        int carried = 0;
        int remaining = 0;
        for (int slot = 0; slot < bottles; slot++) {
            ItemStack potion = stand.getItem(slot);
            ItemStack leftover = c.companion().inventory().addItem(potion.copy());
            carried += potion.getCount() - leftover.getCount();
            remaining += leftover.getCount();
            stand.setItem(slot, leftover);
        }
        List<String> ingredients = new ArrayList<>(steps.size());
        for (Step step : steps) {
            ingredients.add("1 " + label(step.ingredient().label()));
        }
        summary = "brewed " + (carried + remaining) + " " + label(target) + " potion(s): " + carried
                + " in my inventory, " + remaining + " in the stand at " + coords(stand.getBlockPos()) + "; consumed "
                + String.join(", ", ingredients) + " and " + stepIndex + " fuel uses";
        batchStand = null;
        return TaskStatus.SUCCESS;
    }

    @Override
    public void stop(TaskContext c) {
        super.stop(c);
        returnBatch(c);
    }

    @Override
    public void onTerminal(TaskContext c) {
        returnBatch(c);
    }

    private void returnBatch(TaskContext c) {
        BlockPos pos = batchStand;
        batchStand = null;
        if (pos == null
                || !c.level().dimension().identifier().equals(batchDimension)
                || stepIndex >= steps.size()
                || !c.level().hasChunkAt(pos)
                || !(c.level().getBlockEntity(pos) instanceof BrewingStandBlockEntity stand)
                || stand != observedStand
                || !c.companion().hands().canInteract(pos)
                || stand.isLocked()) {
            return;
        }
        Step step = steps.get(stepIndex);
        ItemStack ingredient = stand.getItem(INGREDIENT_SLOT);
        boolean pending =
                staged && ingredient.getCount() == 1 && step.ingredient().test(ingredient);
        boolean input = standMatches(stand, step.input()) && (pending || !staged && ingredient.isEmpty());
        if (!input) {
            return;
        }
        for (int slot = 0; slot < bottles; slot++) {
            InventoryOps.insertOrDrop(c.companion(), stand.removeItem(slot, 1));
        }
        if (pending) {
            InventoryOps.insertOrDrop(c.companion(), stand.removeItem(INGREDIENT_SLOT, 1));
        }
        ItemStack fuel = stand.getItem(FUEL_SLOT);
        if (isAddedFuel(fuel)) {
            InventoryOps.insertOrDrop(c.companion(), stand.removeItem(FUEL_SLOT, 1));
        }
    }

    private boolean isAddedFuel(ItemStack fuel) {
        return !addedFuel.isEmpty()
                && fuel.getCount() == 1
                && BuiltInRegistries.ITEM.getKey(fuel.getItem()).toString().equals(addedFuel);
    }

    private boolean standMatches(Container stand, ResourceKey<Potion> potion) {
        for (int slot = 0; slot < BOTTLE_SLOTS; slot++) {
            ItemStack stack = stand.getItem(slot);
            if (slot < bottles ? stack.getCount() != 1 || !isPotion(stack, potion) : !stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private TaskStatus fail(String message) {
        summary = message;
        return TaskStatus.FAILED;
    }

    private static int countPotions(Container inventory, ResourceKey<Potion> potion) {
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isPotion(stack, potion)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean isPotion(ItemStack stack, ResourceKey<Potion> potion) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return stack.is(Items.POTION)
                && contents != null
                && contents.potion().isPresent()
                && contents.potion().get().is(potion);
    }

    private static ResourceKey<Potion> potionKey(String id) {
        return ResourceKey.create(Registries.POTION, Identifier.parse(id));
    }

    private static String label(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1).replace('_', ' ') : id;
    }
}
