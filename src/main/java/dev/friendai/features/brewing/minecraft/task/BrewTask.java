package dev.friendai.features.brewing.minecraft.task;

import dev.friendai.features.brewing.BrewPlanner;
import dev.friendai.features.brewing.BrewRecipe;
import dev.friendai.features.brewing.BrewStep;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.MenuBlockTask;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

/**
 * Brews a supported potion at a found brewing stand through the vanilla menu, one stand operation at a time.
 *
 * <p>The task keeps the intermediate potions in the stand's bottle slots between steps, so a resumed stand does not
 * repeat a completed operation: each tick it reads the potion component and advances past any step whose result is
 * already in the bottles. A vanilla operation takes 400 ticks; the task waits 400 plus {@link #WAIT_MARGIN} and then
 * reads the component, never a timeout equal to the brew duration. Missing bottles, ingredient, fuel or stand are
 * typed failures that name the piece.
 */
public final class BrewTask extends MenuBlockTask {
    /** Bottle slots in a brewing stand. */
    private static final int FIRST_BOTTLE = 0;

    private static final int LAST_BOTTLE = 2;
    private static final int INGREDIENT_SLOT = 3;
    private static final int FUEL_SLOT = 4;
    /** A bounded margin over the vanilla 400-tick operation, so a slow tick never reads mid-brew. */
    static final int WAIT_MARGIN = 60;

    private final String target;
    private final int bottles;
    private BrewRecipe plan;
    private int stepIndex;
    private int waited;

    public BrewTask(String target, int bottles, @Nullable BlockPos stand) {
        this(target, bottles, stand, 0);
    }

    /** Restores a task from a checkpoint; {@code stepIndex} is the next unfinished step. */
    public BrewTask(String target, int bottles, @Nullable BlockPos stand, int stepIndex) {
        super(Blocks.BREWING_STAND, "brewing stand", stand);
        this.target = target == null ? "" : target.strip().toLowerCase(java.util.Locale.ROOT);
        if (bottles < 1 || bottles > 3) {
            throw new IllegalArgumentException("bottles must be 1 to 3");
        }
        this.bottles = bottles;
        this.plan = BrewPlanner.plan(this.target).orElse(null);
        this.stepIndex =
                Math.max(0, Math.min(stepIndex, plan == null ? 0 : plan.steps().size()));
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
    public String saveData() {
        return target + "|" + bottles + "|" + stepIndex;
    }

    /** The next unfinished step, for a checkpoint restore test. */
    int stepIndex() {
        return stepIndex;
    }

    @Override
    protected TaskStatus operate(TaskContext c, BlockPos pos) {
        if (plan == null) {
            summary = "unsupported potion: " + target;
            return TaskStatus.FAILED;
        }
        if (stepIndex >= plan.steps().size()) {
            return TaskStatus.SUCCESS;
        }
        if (!(c.companion().hands().openMenu(pos) instanceof BrewingStandMenu menu)) {
            summary = "the brewing stand at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " would not open";
            return TaskStatus.FAILED;
        }
        return brewStep(c, menu);
    }

    private TaskStatus brewStep(TaskContext c, BrewingStandMenu menu) {
        BrewStep step = plan.steps().get(stepIndex);
        // An operation completed between ticks: the bottles already hold the step's result.
        if (bottleCount(menu, step.toPotion()) == bottles) {
            return advance(c, menu);
        }
        // First step: take the starting potions (water bottles) from the inventory.
        if (bottleCount(menu, step.fromPotion()) < bottles) {
            if (!fillBottles(c, menu, step.fromPotion())) {
                return fail("I do not have " + bottles + " " + label(step.fromPotion()) + " bottle(s)");
            }
        }
        if (!ensureIngredient(c, menu, step)) {
            return fail("I do not have " + label(step.ingredient()));
        }
        if (!ensureFuel(c, menu)) {
            return fail("I do not have brewing fuel (blaze powder)");
        }
        int brewing = menu.getBrewingTicks();
        if (brewing > 0) {
            waited++;
            if (waited > BrewRecipe.OPERATION_TICKS + WAIT_MARGIN) {
                return fail("the stand did not finish brewing " + label(step.toPotion()));
            }
            return TaskStatus.RUNNING;
        }
        // The block entity starts the operation on its next tick; wait for it rather than spinning.
        waited++;
        if (waited > BrewRecipe.OPERATION_TICKS + WAIT_MARGIN) {
            return fail("the stand did not start brewing " + label(step.toPotion()));
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus advance(TaskContext c, BrewingStandMenu menu) {
        stepIndex++;
        waited = 0;
        if (stepIndex >= plan.steps().size()) {
            return TaskStatus.SUCCESS;
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus finish(TaskContext c) {
        int produced = 0;
        for (int slot = FIRST_BOTTLE; slot <= LAST_BOTTLE; slot++) {
            // The menu is already closed here only on the terminal path; count the plan's bottles when it is not open.
            produced += bottles;
            break;
        }
        summary = "brewed " + bottles + " " + label(target) + " using " + String.join(", ", labels(plan.ingredients()));
        return TaskStatus.SUCCESS;
    }

    private TaskStatus fail(String message) {
        summary = message;
        return TaskStatus.FAILED;
    }

    /** Takes {@code bottles} potions of the given id from the inventory into the empty bottle slots. */
    private boolean fillBottles(TaskContext c, BrewingStandMenu menu, String potionId) {
        List<ItemStack> taken = takePotions(c, potionId, bottles);
        if (taken.isEmpty()) {
            return false;
        }
        int slot = FIRST_BOTTLE;
        for (ItemStack stack : taken) {
            if (slot > LAST_BOTTLE) {
                break;
            }
            menu.getSlot(slot).set(stack);
            slot++;
        }
        return bottleCount(menu, potionId) >= bottles;
    }

    private boolean ensureIngredient(TaskContext c, BrewingStandMenu menu, BrewStep step) {
        ItemStack current = menu.getSlot(INGREDIENT_SLOT).getItem();
        if (!current.isEmpty() && matches(current, step.ingredient())) {
            return true;
        }
        if (!current.isEmpty()) {
            InventoryOps.insertOrDrop(c.companion(), current.copy());
            menu.getSlot(INGREDIENT_SLOT).set(ItemStack.EMPTY);
        }
        Optional<ItemMatcher> matcher = ItemMatcher.parse(step.ingredient());
        if (matcher.isEmpty()) {
            return false;
        }
        List<ItemStack> taken = InventoryOps.remove(c.companion().inventory(), matcher.get(), 1);
        if (taken.isEmpty()) {
            return false;
        }
        menu.getSlot(INGREDIENT_SLOT).set(taken.getFirst());
        return true;
    }

    private boolean ensureFuel(TaskContext c, BrewingStandMenu menu) {
        if (menu.getFuel() > 0) {
            return true;
        }
        ItemStack fuel = menu.getSlot(FUEL_SLOT).getItem();
        if (!fuel.isEmpty() && fuel.has(DataComponents.BREWING_FUEL)) {
            return true;
        }
        for (int i = 0; i < c.companion().inventory().getContainerSize(); i++) {
            ItemStack stack = c.companion().inventory().getItem(i);
            if (!stack.isEmpty() && stack.has(DataComponents.BREWING_FUEL)) {
                ItemStack taken = stack.copyWithCount(1);
                stack.shrink(1);
                if (stack.isEmpty()) {
                    c.companion().inventory().setItem(i, ItemStack.EMPTY);
                }
                c.companion().inventory().setChanged();
                menu.getSlot(FUEL_SLOT).set(taken);
                return true;
            }
        }
        return false;
    }

    private int bottleCount(BrewingStandMenu menu, String potionId) {
        int count = 0;
        for (int slot = FIRST_BOTTLE; slot <= LAST_BOTTLE; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!stack.isEmpty() && potionId(stack).equals(potionId)) {
                count++;
            }
        }
        return count;
    }

    private List<ItemStack> takePotions(TaskContext c, String potionId, int max) {
        List<ItemStack> taken = new ArrayList<>(max);
        for (int i = 0; i < c.companion().inventory().getContainerSize() && taken.size() < max; i++) {
            ItemStack stack = c.companion().inventory().getItem(i);
            if (stack.isEmpty() || !potionId(stack).equals(potionId)) {
                continue;
            }
            ItemStack one = stack.copyWithCount(1);
            stack.shrink(1);
            if (stack.isEmpty()) {
                c.companion().inventory().setItem(i, ItemStack.EMPTY);
            }
            taken.add(one);
        }
        c.companion().inventory().setChanged();
        return taken;
    }

    private static boolean matches(ItemStack stack, String itemId) {
        return ItemMatcher.parse(itemId).map(m -> m.test(stack)).orElse(false);
    }

    private static String potionId(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return "";
        }
        return contents.potion()
                .flatMap(
                        holder -> holder.unwrapKey().map(key -> key.identifier().toString()))
                .orElse("");
    }

    private static String label(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1).replace('_', ' ') : id;
    }

    private static List<String> labels(List<String> ids) {
        List<String> labels = new ArrayList<>(ids.size());
        for (String id : ids) {
            labels.add(label(id));
        }
        return labels;
    }
}
