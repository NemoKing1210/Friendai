package dev.friendai.task.builtin;

import dev.friendai.crafting.CraftPlan;
import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.crafting.CraftPlanner;
import dev.friendai.crafting.CraftStep;
import dev.friendai.crafting.CraftText;
import dev.friendai.crafting.Ingredient;
import dev.friendai.crafting.Recipe;
import dev.friendai.crafting.Station;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.autonomy.ProtectionText;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.crafting.RecipeIndex;
import dev.friendai.task.crafting.Stations;
import dev.friendai.task.interact.PlaceSpot;
import dev.friendai.task.inventory.DropSettle;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.mining.BlockBreaker;
import dev.friendai.task.nav.Approach;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.block.Block;

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

    /** How far a station the companion places is looked for. */
    private static final int PLACE_RADIUS = 4;

    /** How long a taken-back station gets to land and leave its pickup delay before the next one. */
    private static final int SETTLE_TICKS = 20;

    /** What the current station step is doing: nothing, walking to a block, or walking to a placement spot. */
    private enum StationPhase { NONE, APPROACH_EXISTING, APPROACH_SPOT }

    private RecipeIndex index;
    private CraftPlan plan;
    private String failure;
    /** How many of the target the companion already carried when there was nothing left to craft. */
    private int alreadyHad;
    private int stepIndex;
    private int stepTicks;
    private int craftsDone;
    private String summary = "";

    private StationPhase phase = StationPhase.NONE;
    private int stationStep = -1;
    private Approach approach;
    private BlockPos stationPos;
    private BlockPos spot;
    private BlockPos against;
    private Direction face;
    private final Map<Station, BlockPos> placedStations = new LinkedHashMap<>();
    private final Map<Station, BlockPos> usedStations = new LinkedHashMap<>();
    private final List<String> stationNotes = new ArrayList<>();
    private final List<Station> takeBacks = new ArrayList<>();
    private boolean leavingStarted;
    private int takeIndex;
    private BlockBreaker breaker;
    /** The wait for a taken-back station's drop to land, ticked before moving on to the next one. */
    private DropSettle settle;

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
            if (index.producing(item).stream().noneMatch(recipe -> !recipe.station().isCooking())) {
                failure = item + " has no crafting, stonecutter or smithing recipe";
                return;
            }
            Map<String, Integer> items = inventoryMap(c.companion());
            plan = CraftPlanner.planNew(
                    item, count, items, index, Stations.available(c.level(), c.companion()));
            if (!plan.ok()) {
                int held = items.getOrDefault(item, 0);
                if (held >= count) {
                    // Nothing can be crafted, but the companion already carries what was asked for: say so instead
                    // of failing on ingredients it does not need.
                    alreadyHad = held;
                    plan = new CraftPlan(List.of(), Map.of(), Set.of(), Set.of());
                } else {
                    failure = "can't craft " + count + " " + item + "; " + CraftText.missingText(plan.missing());
                }
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
            return tickLeaving(c);
        }
        CraftStep step = plan.steps().get(stepIndex);
        if (step.recipe().station() != Station.INVENTORY) {
            return tickStation(c, step);
        }
        return tickInventory(c, step);
    }

    private TaskStatus tickInventory(TaskContext c, CraftStep step) {
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
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        settle = null;
        c.companion().getNavigation().stop();
        if (breaker != null) {
            breaker.stop(c);
            breaker = null;
        }
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
    public List<DebugTarget> debugTargets() {
        if (plan == null || stepIndex >= plan.steps().size()) {
            if (settle != null || takeIndex >= takeBacks.size()) {
                return List.of();
            }
            BlockPos pos = breaker == null || breaker.target() == null
                    ? placedStations.get(takeBacks.get(takeIndex)) : breaker.target();
            if (pos == null) {
                return List.of();
            }
            return List.of(new DebugTarget(pos.getX(), pos.getY(), pos.getZ(), DebugAction.BREAK,
                    "Take back " + takeBacks.get(takeIndex).noun()));
        }
        if (plan == null || stepIndex >= plan.steps().size()) {
            return List.of();
        }
        BlockPos pos = phase == StationPhase.APPROACH_SPOT ? spot : stationPos;
        if (pos == null) {
            return List.of();
        }
        CraftStep step = plan.steps().get(stepIndex);
        DebugAction action = phase == StationPhase.APPROACH_SPOT ? DebugAction.PLACE : DebugAction.CRAFT;
        String detail = action == DebugAction.PLACE
                ? "Place " + step.recipe().station().noun()
                : "Craft " + step.recipe().output();
        return List.of(new DebugTarget(pos.getX(), pos.getY(), pos.getZ(), action, detail));
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    /** One tick of a step that needs a station: resolve it and walk to it, then craft at it. */
    private TaskStatus tickStation(TaskContext c, CraftStep step) {
        if (stationStep != stepIndex) {
            resetStation();
            stationStep = stepIndex;
        }
        if (phase == StationPhase.APPROACH_EXISTING || phase == StationPhase.APPROACH_SPOT) {
            return tickApproach(c, step);
        }
        if (stationPos == null) {
            return findOrPlaceStation(c, step);
        }
        return craftAtStation(c, step);
    }

    private TaskStatus tickApproach(TaskContext c, CraftStep step) {
        Station station = step.recipe().station();
        Approach.State state = approach.tick(c.companion());
        if (state == Approach.State.MOVING) {
            return TaskStatus.RUNNING;
        }
        if (phase == StationPhase.APPROACH_SPOT) {
            if (state == Approach.State.UNREACHABLE) {
                approach.stop(c.companion());
                summary = "couldn't place a " + station.noun() + ": can't reach " + coords(spot);
                return TaskStatus.FAILED;
            }
            return placeStation(c, step);
        }
        if (state == Approach.State.UNREACHABLE) {
            approach.stop(c.companion());
            summary = "can't reach the " + station.noun() + " at " + coords(stationPos);
            return TaskStatus.FAILED;
        }
        approach = null;
        phase = StationPhase.NONE;
        return craftAtStation(c, step);
    }

    /**
     * Looks for the station block again at this moment; when there is none, the companion places one of the items
     * the plan crafted for it. A station this task placed, or one it left out for the next craft, is reused rather
     * than replaced; only a station this task placed itself is ever taken back.
     */
    private TaskStatus findOrPlaceStation(TaskContext c, CraftStep step) {
        Station station = step.recipe().station();
        CompanionEntity companion = c.companion();
        long now = c.level().getGameTime();
        BlockPos own = placedStations.get(station);
        if (own != null) {
            Block block = Stations.block(station);
            // A station this task placed is approached even when the companion has walked out of scan range.
            if (block != null && c.level().getBlockState(own).is(block)) {
                stationPos = own;
                phase = StationPhase.APPROACH_EXISTING;
                approach = new Approach(own);
                return TaskStatus.RUNNING;
            }
            placedStations.remove(station);
        }
        OptionalLong leased = companion.stationLease().held(station, now);
        if (leased.isPresent() && leaseUsable(c, station, BlockPos.of(leased.getAsLong()), now)) {
            return TaskStatus.RUNNING;
        }
        Optional<BlockPos> nearby = Stations.nearby(companion, station);
        if (nearby.isPresent()) {
            BlockPos pos = nearby.get();
            usedStations.putIfAbsent(station, pos);
            stationPos = pos;
            phase = StationPhase.APPROACH_EXISTING;
            approach = new Approach(pos);
            return TaskStatus.RUNNING;
        }
        String noun = station.noun();
        String stationItem = index.stationItem(station);
        if (stationItem == null || !hasItem(companion, stationItem)) {
            summary = "couldn't place a " + noun + ": no " + noun + " in the inventory";
            return TaskStatus.FAILED;
        }
        Optional<PlaceSpot.Spot> found = PlaceSpot.near(c.level(), companion, PLACE_RADIUS);
        if (found.isEmpty()) {
            summary = "couldn't place a " + noun + ": no free spot nearby";
            return TaskStatus.FAILED;
        }
        PlaceSpot.Spot chosen = found.get();
        spot = chosen.pos();
        against = chosen.against();
        face = chosen.face();
        phase = StationPhase.APPROACH_SPOT;
        approach = new Approach(spot);
        return TaskStatus.RUNNING;
    }

    /** Whether the station the companion left out before is still there; using it again renews its lease. */
    private boolean leaseUsable(TaskContext c, Station station, BlockPos pos, long now) {
        Block block = Stations.block(station);
        if (block == null || !c.level().getBlockState(pos).is(block)) {
            c.companion().stationLease().release(station);
            return false;
        }
        c.companion().stationLease().keep(station, pos.asLong(), now);
        usedStations.putIfAbsent(station, pos);
        stationPos = pos;
        phase = StationPhase.APPROACH_EXISTING;
        approach = new Approach(pos);
        return true;
    }

    private TaskStatus placeStation(TaskContext c, CraftStep step) {
        Station station = step.recipe().station();
        String noun = station.noun();
        Optional<String> problem = PlaceSpot.problem(c.level(), spot, c.companion());
        if (problem.isPresent()) {
            summary = "couldn't place a " + noun + ": " + problem.get();
            return TaskStatus.FAILED;
        }
        Optional<PlaceSpot.Spot> current = PlaceSpot.spotAt(c.level(), spot);
        if (current.isEmpty()) {
            summary = "couldn't place a " + noun + ": nothing to place against at " + coords(spot);
            return TaskStatus.FAILED;
        }
        against = current.get().against();
        face = current.get().face();
        if (!c.companion().hands().canInteract(spot)) {
            summary = "couldn't place a " + noun + ": " + coords(spot) + " is " + ProtectionText.describe(c, spot);
            return TaskStatus.FAILED;
        }
        String stationItem = index.stationItem(station);
        ItemStack held = stationItem == null ? ItemStack.EMPTY : takeItem(c.companion(), stationItem);
        if (held.isEmpty()) {
            summary = "couldn't place a " + noun + ": no " + noun + " in the inventory";
            return TaskStatus.FAILED;
        }
        c.companion().hands().place(held, against, face);
        Block block = Stations.block(station);
        if (block != null && c.level().getBlockState(spot).is(block)) {
            stationPos = spot;
            placedStations.put(station, spot);
            c.companion().stationLease().keep(station, spot.asLong(), c.level().getGameTime());
            spot = null;
            approach = null;
            phase = StationPhase.NONE;
            return TaskStatus.RUNNING;
        }
        summary = "couldn't place a " + noun + ": the block did not appear at " + coords(spot);
        return TaskStatus.FAILED;
    }

    private TaskStatus craftAtStation(TaskContext c, CraftStep step) {
        CompanionEntity companion = c.companion();
        double x = stationPos.getX() + 0.5;
        double y = stationPos.getY() + 0.5;
        double z = stationPos.getZ() + 0.5;
        companion.getLookControl().setLookAt(x, y, z);
        stepTicks++;
        if (stepTicks < CRAFT_TICKS) {
            return TaskStatus.RUNNING;
        }
        stepTicks = 0;
        swing(companion);
        boolean crafted = switch (step.recipe().station()) {
            case CRAFTING_TABLE -> craftOnce(c, step);
            case STONECUTTER -> stonecutterOnce(c, step);
            case SMITHING_TABLE -> smithingOnce(c, step);
            case INVENTORY -> false;
            case FURNACE, SMOKER, BLAST_FURNACE, CAMPFIRE -> false;
        };
        if (!crafted) {
            summary = "the inventory changed while crafting: " + remainingMissing(c);
            return TaskStatus.FAILED;
        }
        craftsDone++;
        if (craftsDone >= step.times()) {
            stepIndex++;
            craftsDone = 0;
            resetStation();
        }
        return TaskStatus.RUNNING;
    }

    /** One stonecutter craft: a single matching item in, the recipe's own result count out. */
    private boolean stonecutterOnce(TaskContext c, CraftStep step) {
        Optional<RecipeHolder<?>> holder = index.holder(step.recipe().id());
        if (holder.isEmpty() || !(holder.get().value() instanceof StonecutterRecipe recipe)) {
            return false;
        }
        CompanionEntity companion = c.companion();
        int[] taken = new int[companion.inventory().getContainerSize() + 1];
        ItemStack input = takeConcrete(companion, recipe.input(), taken);
        if (input.isEmpty()) {
            return false;
        }
        SingleRecipeInput recipeInput = new SingleRecipeInput(input);
        if (!recipe.matches(recipeInput, c.level())) {
            return false;
        }
        ItemStack result = recipe.assemble(recipeInput);
        if (result.isEmpty()) {
            return false;
        }
        consume(companion, taken);
        InventoryOps.insertOrDrop(companion, result);
        return true;
    }

    /** One smithing craft: template, base and addition each from one slot; the result keeps the base's components. */
    private boolean smithingOnce(TaskContext c, CraftStep step) {
        Optional<RecipeHolder<?>> holder = index.holder(step.recipe().id());
        if (holder.isEmpty() || !(holder.get().value() instanceof SmithingRecipe recipe)) {
            return false;
        }
        CompanionEntity companion = c.companion();
        int[] taken = new int[companion.inventory().getContainerSize() + 1];
        ItemStack template = ItemStack.EMPTY;
        if (recipe.templateIngredient().isPresent()) {
            template = takeConcrete(companion, recipe.templateIngredient().get(), taken);
            if (template.isEmpty()) {
                return false;
            }
        }
        ItemStack base = takeConcrete(companion, recipe.baseIngredient(), taken);
        if (base.isEmpty()) {
            return false;
        }
        ItemStack addition = ItemStack.EMPTY;
        if (recipe.additionIngredient().isPresent()) {
            addition = takeConcrete(companion, recipe.additionIngredient().get(), taken);
            if (addition.isEmpty()) {
                return false;
            }
        }
        SmithingRecipeInput recipeInput = new SmithingRecipeInput(template, base, addition);
        if (!recipe.matches(recipeInput, c.level())) {
            return false;
        }
        ItemStack result = recipe.assemble(recipeInput);
        if (result.isEmpty()) {
            return false;
        }
        consume(companion, taken);
        InventoryOps.insertOrDrop(companion, result);
        return true;
    }

    private void resetStation() {
        phase = StationPhase.NONE;
        stationStep = -1;
        approach = null;
        stationPos = null;
        spot = null;
        against = null;
        face = null;
        stepTicks = 0;
    }

    /** After the last step: leave or take back every station this task placed, then summarise. */
    private TaskStatus tickLeaving(TaskContext c) {
        if (!leavingStarted) {
            planLeaving(c);
        }
        if (settle != null) {
            if (!settle.tick(c)) {
                return TaskStatus.RUNNING;
            }
            settle = null;
            takeIndex++;
            return TaskStatus.RUNNING;
        }
        if (takeIndex < takeBacks.size()) {
            Station station = takeBacks.get(takeIndex);
            BlockPos pos = placedStations.get(station);
            if (breaker == null) {
                Block block = Stations.block(station);
                breaker = new BlockBreaker(pos, state -> block != null && state.is(block));
            }
            BlockBreaker.State state = breaker.tick(c);
            if (state == BlockBreaker.State.WORKING) {
                return TaskStatus.RUNNING;
            }
            String why = breaker.reason(state);
            if (state == BlockBreaker.State.BROKEN || state == BlockBreaker.State.GONE) {
                breaker = null;
                stationNotes.add("placed a " + station.noun() + " at " + coords(pos) + " and took it back");
                if (state == BlockBreaker.State.BROKEN) {
                    // The drop lands after the vanilla pickup delay: take it before moving on.
                    settle = new DropSettle(SETTLE_TICKS, DropSettle.INTERACT_RADIUS, null);
                    return TaskStatus.RUNNING;
                }
            } else {
                breaker.stop(c);
                breaker = null;
                stationNotes.add("placed a " + station.noun() + " at " + coords(pos)
                        + " but couldn't take it back: " + why);
            }
            takeIndex++;
            return TaskStatus.RUNNING;
        }
        return finish();
    }

    private void planLeaving(TaskContext c) {
        leavingStarted = true;
        long now = c.level().getGameTime();
        for (Map.Entry<Station, BlockPos> entry : placedStations.entrySet()) {
            Station station = entry.getKey();
            BlockPos pos = entry.getValue();
            boolean base = policy == StationPolicy.AUTO && Stations.looksLikeBase(c.companion(), pos);
            if (policy == StationPolicy.KEEP || base) {
                c.companion().stationLease().release(station);
                stationNotes.add("placed a " + station.noun() + " at " + coords(pos)
                        + " and left it there" + (base ? " (looks like a base)" : ""));
            } else if (policy == StationPolicy.AUTO) {
                // Crafting several things in a row is several tasks: keep it out so the next craft reuses it, and
                // fetch it back once nothing follows.
                c.companion().stationLease().keep(station, pos.asLong(), now);
                stationNotes.add("placed a " + station.noun() + " at " + coords(pos) + " and left it there for now");
            } else {
                c.companion().stationLease().release(station);
                takeBacks.add(station);
            }
        }
    }

    private TaskStatus finish() {
        StringBuilder text = new StringBuilder();
        if (plan.steps().isEmpty()) {
            text.append("already had ").append(alreadyHad).append(' ').append(item).append("; crafted nothing");
        } else {
            CraftStep last = plan.steps().getLast();
            text.append("crafted ").append(produced(last)).append(' ').append(item);
            if (plan.steps().size() > 1) {
                StringJoiner also = new StringJoiner(", ");
                for (CraftStep step : plan.steps().subList(0, plan.steps().size() - 1)) {
                    also.add(produced(step) + " " + step.recipe().output());
                }
                text.append(" (also crafted ").append(also).append(')');
            }
        }
        usedStations.forEach((station, pos) ->
                text.append("; used the ").append(station.noun()).append(" at ").append(coords(pos)));
        for (String note : stationNotes) {
            text.append("; ").append(note);
        }
        summary = text.toString();
        return TaskStatus.SUCCESS;
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static boolean hasItem(CompanionEntity companion, String itemId) {
        SimpleContainer inventory = companion.inventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (idOf(inventory.getItem(slot)).equals(itemId)) {
                return true;
            }
        }
        return idOf(companion.getMainHandItem()).equals(itemId);
    }

    /** Removes one item of the given id, inventory slots first and the main hand last. */
    private static ItemStack takeItem(CompanionEntity companion, String itemId) {
        SimpleContainer inventory = companion.inventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && idOf(stack).equals(itemId)) {
                ItemStack one = stack.split(1);
                if (stack.isEmpty()) {
                    inventory.setItem(slot, ItemStack.EMPTY);
                }
                inventory.setChanged();
                return one;
            }
        }
        ItemStack hand = companion.getMainHandItem();
        if (!hand.isEmpty() && idOf(hand).equals(itemId)) {
            ItemStack one = hand.split(1);
            if (hand.isEmpty()) {
                companion.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
            return one;
        }
        return ItemStack.EMPTY;
    }

    private static String idOf(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
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
        return takeConcrete(companion, stack -> stack.getItem() == picked.value() && ingredient.test(stack), taken);
    }

    /** The first matching slot of the inventory, then the main hand, not already reserved by this craft. */
    private static ItemStack takeConcrete(
            CompanionEntity companion, net.minecraft.world.item.crafting.Ingredient ingredient, int[] taken) {
        return takeConcrete(companion, ingredient::test, taken);
    }

    private static ItemStack takeConcrete(CompanionEntity companion, Predicate<ItemStack> matches, int[] taken) {
        SimpleContainer inventory = companion.inventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getCount() <= taken[slot] || !matches.test(stack)) {
                continue;
            }
            taken[slot]++;
            return stack.copyWithCount(1);
        }
        int handSlot = inventory.getContainerSize();
        ItemStack hand = companion.getMainHandItem();
        if (hand.getCount() > taken[handSlot] && matches.test(hand)) {
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
            CraftPlan rest = CraftPlanner.planNew(item, remaining, inventoryMap(c.companion()), index,
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
