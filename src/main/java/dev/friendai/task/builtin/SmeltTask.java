package dev.friendai.task.builtin;

import dev.friendai.crafting.Recipe;
import dev.friendai.crafting.Station;
import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.cooking.FuelPolicy;
import dev.friendai.task.cooking.FurnaceJob;
import dev.friendai.task.cooking.FurnaceSlots;
import dev.friendai.task.crafting.RecipeIndex;
import dev.friendai.task.crafting.Stations;
import dev.friendai.task.interact.PlaceSpot;
import dev.friendai.task.inventory.DropSettle;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.mining.BlockBreaker;
import dev.friendai.task.nav.Approach;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Smelts or cooks items from the inventory with real fuel and time. Short jobs are waited out; long ones are
 * loaded into up to three furnaces and reported by the watcher when they finish.
 */
public final class SmeltTask implements Task {
    /** How many free stations one job spreads over. */
    static final int MAX_FURNACES = 3;

    /** How far a station the companion places is looked for. */
    private static final int PLACE_RADIUS = 4;

    /** How long a taken-back station gets to land and leave its pickup delay before the next one. */
    private static final int SETTLE_TICKS = 20;

    private enum Phase {
        CRAFT_STATION,
        SEEK_SPOT,
        APPROACH_SPOT,
        PLACE_STATION,
        APPROACH_STATION,
        LOAD,
        WAIT,
        LEAVING,
        DONE
    }

    private record Target(BlockPos pos, AbstractFurnaceBlockEntity furnace, boolean placedByMe) {}

    private final ItemMatcher input;
    private final int count;
    private final @Nullable Station forced;
    private final @Nullable ItemMatcher fuel;
    private final boolean forceWait;
    private final CraftTask.StationPolicy policy;
    private final int waitLimitTicks;

    private RecipeIndex index;
    private String failure;
    private String summary = "";
    private List<ItemStack> selected = List.of();
    private Station kind;
    private int cookTicks;
    private boolean waitMode;
    private String fuelNote;

    private Phase phase = Phase.LOAD;
    private int targetIndex;
    private Approach approach;
    private final List<BlockPos> pendingStations = new ArrayList<>();
    private final List<Target> targets = new ArrayList<>();
    private CraftTask craftTask;

    private BlockPos spot;
    private BlockPos against;
    private Direction face;

    private int waitTicks;
    private int collected;
    private String waitSummaryBase = "";
    private final List<BlockPos> takeBacks = new ArrayList<>();
    private final List<String> stationNotes = new ArrayList<>();
    private int takeBackIndex;
    private BlockBreaker breaker;
    /** The wait for a taken-back station's drop to land, ticked before moving on to the next one. */
    private DropSettle settle;

    private int loadedCount;
    private int stationsLoaded;
    private String outputId;
    private final Set<String> fuelsUsed = new LinkedHashSet<>();
    private final List<ItemStack> waitingInputs = new ArrayList<>();
    private Approach waitApproach;

    public SmeltTask(
            ItemMatcher input,
            int count,
            @Nullable Station forced,
            @Nullable ItemMatcher fuel,
            boolean forceWait,
            CraftTask.StationPolicy policy,
            int waitLimitTicks) {
        if (input == null) {
            throw new IllegalArgumentException("smelt input must not be empty");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("smelt count must be positive: " + count);
        }
        this.input = input;
        this.count = count;
        this.forced = forced;
        this.fuel = fuel;
        this.forceWait = forceWait;
        this.policy = policy == null ? CraftTask.StationPolicy.AUTO : policy;
        this.waitLimitTicks = Math.max(1, waitLimitTicks);
    }

    @Override
    public String type() {
        return "smelt_items";
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public void start(TaskContext c) {
        try {
            plan(c);
        } catch (RuntimeException e) {
            failure = "can't smelt " + count + " " + input.label();
        }
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (failure != null) {
            summary = failure;
            return TaskStatus.FAILED;
        }
        TaskStatus status = switch (phase) {
            case CRAFT_STATION -> tickCraft(c);
            case SEEK_SPOT -> findSpot(c);
            case APPROACH_SPOT -> tickApproachSpot(c);
            case PLACE_STATION -> placeStation(c);
            case APPROACH_STATION -> tickApproachStation(c);
            case LOAD -> loadAll(c);
            case WAIT -> tickWait(c);
            case LEAVING -> tickLeaving(c);
            case DONE -> TaskStatus.SUCCESS;
        };
        return status;
    }

    @Override
    public void stop(TaskContext c) {
        settle = null;
        c.companion().getNavigation().stop();
        if (craftTask != null) {
            craftTask.stop(c);
            craftTask = null;
        }
        if (breaker != null) {
            breaker.stop(c);
            breaker = null;
        }
    }

    @Override
    public String describe() {
        if (waitMode && phase == Phase.WAIT) {
            return "waiting for " + targets.size() + " " + plural(targets.size()) + ": " + collected + "/" + total()
                    + " done";
        }
        return "smelting " + count + " " + input.label();
    }

    @Override
    public List<DebugTarget> debugTargets() {
        if (craftTask != null) {
            return craftTask.debugTargets();
        }
        if (phase == Phase.APPROACH_SPOT || phase == Phase.PLACE_STATION) {
            return target(spot, DebugAction.PLACE, "Place " + (kind == null ? "furnace" : kind.noun()));
        }
        if (phase == Phase.LEAVING) {
            BlockPos pos = breaker == null
                    ? (takeBackIndex < takeBacks.size() ? takeBacks.get(takeBackIndex) : null)
                    : breaker.target();
            return target(pos, DebugAction.BREAK, "Take back furnace");
        }
        if (phase == Phase.APPROACH_STATION && targetIndex < pendingStations.size()) {
            return target(pendingStations.get(targetIndex), DebugAction.SMELT, "Load furnace");
        }
        if (phase == Phase.LOAD && targets.isEmpty() && !pendingStations.isEmpty()) {
            return pendingStations.stream()
                    .map(pos -> new DebugTarget(pos.getX(), pos.getY(), pos.getZ(), DebugAction.SMELT, "Load furnace"))
                    .toList();
        }
        if ((phase == Phase.LOAD || phase == Phase.WAIT) && !targets.isEmpty()) {
            return targets.stream()
                    .map(station -> new DebugTarget(
                            station.pos().getX(),
                            station.pos().getY(),
                            station.pos().getZ(),
                            DebugAction.SMELT,
                            "Smelt " + input.label()))
                    .toList();
        }
        return List.of();
    }

    private static List<DebugTarget> target(@Nullable BlockPos pos, DebugAction action, String detail) {
        return pos == null ? List.of() : List.of(new DebugTarget(pos.getX(), pos.getY(), pos.getZ(), action, detail));
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    // ---------------------------------------------------------------- planning

    private void plan(TaskContext c) {
        index = RecipeIndex.of(c.level());
        selected = select(c.companion());
        if (selected.isEmpty()) {
            failure = "I don't have " + input.label();
            return;
        }
        String firstId = idOf(selected.get(0));
        if (index.cookingFrom(firstId).isEmpty()) {
            failure = firstId + " can't be smelted or cooked";
            return;
        }
        for (String id : distinctIds()) {
            if (index.cookingFrom(id).isEmpty()) {
                failure = id + " can't be smelted or cooked";
                return;
            }
        }

        kind = forced != null ? forced : chooseKind(c);
        if (kind == null) {
            failure = "no free station nearby and I can't make one";
            return;
        }
        Recipe recipe = recipeOn(kind, firstId);
        if (recipe == null) {
            if (forced != null) {
                failure = firstId + " can't be cooked " + forced.phrase();
                return;
            }
            kind = Station.FURNACE;
            recipe = recipeOn(kind, firstId);
            if (recipe == null) {
                failure = "no free station nearby and I can't make one";
                return;
            }
        }
        cookTicks = recipe.cookTicks();
        outputId = recipe.output();

        List<BlockPos> free = freeStations(c, kind);
        int stations = free.isEmpty() ? 1 : Math.min(free.size(), MAX_FURNACES);
        int estimate = estimate(kind, total(), stations);
        boolean longJob = !forceWait && estimate > waitLimitTicks;
        if (kind == Station.CAMPFIRE && longJob) {
            if (forced != null) {
                failure = "that would take too long on a campfire; ask me to wait or give me a furnace";
                return;
            }
            kind = Station.FURNACE;
            recipe = recipeOn(kind, firstId);
            if (recipe == null) {
                failure = "that would take too long on a campfire; ask me to wait or give me a furnace";
                return;
            }
            cookTicks = recipe.cookTicks();
            outputId = recipe.output();
            free = freeStations(c, kind);
            stations = free.isEmpty() ? 1 : Math.min(free.size(), MAX_FURNACES);
            estimate = estimate(kind, total(), stations);
        }
        waitMode = forceWait || estimate <= waitLimitTicks;

        if (free.isEmpty()) {
            String stationItem = index.stationItem(kind);
            if (stationItem != null && hasItem(c.companion(), stationItem)) {
                phase = Phase.SEEK_SPOT;
                return;
            }
            if (kind == Station.FURNACE && stationItem != null) {
                craftTask = new CraftTask(stationItem, 1, CraftTask.StationPolicy.KEEP);
                craftTask.start(c);
                phase = Phase.CRAFT_STATION;
                return;
            }
            failure = "no free " + kind.noun() + " nearby and I can't make one";
            return;
        }
        pendingStations.addAll(free);
        phase = Phase.APPROACH_STATION;
    }

    private Station chooseKind(TaskContext c) {
        String firstId = idOf(selected.get(0));
        for (Station candidate : List.of(Station.BLAST_FURNACE, Station.SMOKER, Station.FURNACE)) {
            if (recipeOn(candidate, firstId) != null
                    && kindCoversAll(candidate)
                    && !freeStations(c, candidate).isEmpty()) {
                return candidate;
            }
        }
        if (recipeOn(Station.CAMPFIRE, firstId) != null
                && kindCoversAll(Station.CAMPFIRE)
                && !freeStations(c, Station.CAMPFIRE).isEmpty()) {
            return Station.CAMPFIRE;
        }
        for (Station candidate : List.of(Station.BLAST_FURNACE, Station.SMOKER, Station.FURNACE)) {
            if (recipeOn(candidate, firstId) != null && kindCoversAll(candidate)) {
                String item = index.stationItem(candidate);
                if (item != null && hasItem(c.companion(), item)) {
                    return candidate;
                }
            }
        }
        if (recipeOn(Station.FURNACE, firstId) != null && kindCoversAll(Station.FURNACE)) {
            return Station.FURNACE;
        }
        return null;
    }

    private boolean kindCoversAll(Station station) {
        for (String id : distinctIds()) {
            if (recipeOn(station, id) == null) {
                return false;
            }
        }
        return true;
    }

    private Recipe recipeOn(Station station, String itemId) {
        for (Recipe recipe : index.cookingFrom(itemId)) {
            if (recipe.station() == station) {
                return recipe;
            }
        }
        return null;
    }

    private List<BlockPos> freeStations(TaskContext c, Station station) {
        if (selected.isEmpty()) {
            return List.of();
        }
        Item expected = expectedItem(station, idOf(selected.get(0)));
        if (expected == null) {
            return List.of();
        }
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : Stations.nearbyAll(c.companion(), station, 64)) {
            if (station == Station.CAMPFIRE) {
                if (campfireHasRoom(c.level(), pos)) {
                    found.add(pos);
                }
            } else if (c.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace
                    && FurnaceSlots.isFreeFor(furnace, expectedItemInput(station), expected)) {
                found.add(pos);
            }
            if (found.size() >= MAX_FURNACES) {
                break;
            }
        }
        return found;
    }

    private Item expectedItemInput(Station station) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(idOf(selected.get(0))));
    }

    private Item expectedItem(Station station, String itemId) {
        Recipe recipe = recipeOn(station, itemId);
        if (recipe == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(recipe.output()));
    }

    private static boolean campfireHasRoom(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof CampfireBlockEntity campfire)) {
            return false;
        }
        for (ItemStack stack : campfire.getItems()) {
            if (stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private int estimate(Station station, int total, int stations, int ticks) {
        if (station == Station.CAMPFIRE) {
            int batches = (total + 3) / 4;
            return batches * ticks;
        }
        int perStation = (total + stations - 1) / stations;
        return perStation * ticks;
    }

    private int estimate(Station station, int total, int stations) {
        return estimate(station, total, stations, cookTicks);
    }

    private List<String> distinctIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (ItemStack stack : selected) {
            ids.add(idOf(stack));
        }
        return List.copyOf(ids);
    }

    private List<ItemStack> select(CompanionEntity companion) {
        List<ItemStack> chosen = new ArrayList<>();
        int remaining = count;
        SimpleContainer inventory = companion.inventory();
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !input.test(stack)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            chosen.add(stack.copyWithCount(take));
            remaining -= take;
        }
        return chosen;
    }

    private int total() {
        int total = 0;
        for (ItemStack stack : selected) {
            total += stack.getCount();
        }
        return total;
    }

    private static boolean hasItem(CompanionEntity companion, String itemId) {
        SimpleContainer inventory = companion.inventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && idOf(stack).equals(itemId)) {
                return true;
            }
        }
        return false;
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    // ---------------------------------------------------------------- placement

    private TaskStatus tickCraft(TaskContext c) {
        CraftTask task = craftTask;
        if (task == null) {
            failure = "no free " + kind.noun() + " nearby and I can't make one";
            return TaskStatus.FAILED;
        }
        TaskStatus status = task.tick(c);
        if (status == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        craftTask = null;
        if (status != TaskStatus.SUCCESS) {
            failure = "no free " + kind.noun() + " nearby and I can't make one: " + task.resultSummary();
            return TaskStatus.FAILED;
        }
        phase = Phase.SEEK_SPOT;
        return TaskStatus.RUNNING;
    }

    private TaskStatus findSpot(TaskContext c) {
        Optional<PlaceSpot.Spot> found = PlaceSpot.near(c.level(), c.companion(), PLACE_RADIUS);
        if (found.isEmpty()) {
            summary = "couldn't place a " + kind.noun() + ": no free spot nearby";
            return TaskStatus.FAILED;
        }
        PlaceSpot.Spot chosen = found.get();
        spot = chosen.pos();
        against = chosen.against();
        face = chosen.face();
        approach = new Approach(spot);
        phase = Phase.APPROACH_SPOT;
        return TaskStatus.RUNNING;
    }

    private TaskStatus tickApproachSpot(TaskContext c) {
        Approach.State state = approach.tick(c.companion());
        if (state == Approach.State.MOVING) {
            return TaskStatus.RUNNING;
        }
        if (state == Approach.State.UNREACHABLE) {
            approach.stop(c.companion());
            summary = "couldn't place a " + kind.noun() + ": can't reach the spot";
            return TaskStatus.FAILED;
        }
        approach = null;
        phase = Phase.PLACE_STATION;
        return TaskStatus.RUNNING;
    }

    private TaskStatus placeStation(TaskContext c) {
        String noun = kind.noun();
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
            summary = "couldn't place a " + noun + ": " + coords(spot) + " is protected";
            return TaskStatus.FAILED;
        }
        String stationItem = index.stationItem(kind);
        ItemStack held = stationItem == null ? ItemStack.EMPTY : takeOne(c.companion(), stationItem);
        if (held.isEmpty()) {
            summary = "couldn't place a " + noun + ": no " + noun + " in the inventory";
            return TaskStatus.FAILED;
        }
        c.companion().hands().place(held, against, face);
        if (!isStationBlock(c.level().getBlockState(spot), kind)) {
            summary = "couldn't place a " + noun + ": the block did not appear at " + coords(spot);
            return TaskStatus.FAILED;
        }
        AbstractFurnaceBlockEntity furnace =
                c.level().getBlockEntity(spot) instanceof AbstractFurnaceBlockEntity be ? be : null;
        targets.add(new Target(spot, furnace, true));
        spot = null;
        phase = Phase.LOAD;
        return TaskStatus.RUNNING;
    }

    private static boolean isStationBlock(BlockState state, Station station) {
        if (station == Station.CAMPFIRE) {
            return state.is(BlockTags.CAMPFIRES);
        }
        Block block = Stations.block(station);
        return block != null && state.is(block);
    }

    /** One item of the given id from the inventory, never the main hand. */
    private static ItemStack takeOne(CompanionEntity companion, String itemId) {
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
        return ItemStack.EMPTY;
    }

    // ---------------------------------------------------------------- approaching and loading

    private TaskStatus tickApproachStation(TaskContext c) {
        if (targetIndex >= pendingStations.size()) {
            if (targets.isEmpty()) {
                summary = "can't reach any " + kind.noun();
                return TaskStatus.FAILED;
            }
            phase = Phase.LOAD;
            return TaskStatus.RUNNING;
        }
        BlockPos pos = pendingStations.get(targetIndex);
        if (approach == null) {
            approach = new Approach(pos);
        }
        Approach.State state = approach.tick(c.companion());
        if (state == Approach.State.MOVING) {
            return TaskStatus.RUNNING;
        }
        approach = null;
        targetIndex++;
        if (state == Approach.State.UNREACHABLE) {
            return TaskStatus.RUNNING;
        }
        if (!c.companion().hands().canInteract(pos)) {
            return TaskStatus.RUNNING;
        }
        if (kind == Station.CAMPFIRE) {
            if (!campfireHasRoom(c.level(), pos)) {
                return TaskStatus.RUNNING;
            }
            targets.add(new Target(pos, null, false));
            return TaskStatus.RUNNING;
        }
        if (!(c.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return TaskStatus.RUNNING;
        }
        Item expected = expectedItem(kind, idOf(selected.get(0)));
        Item inputItem = expectedItemInput(kind);
        if (expected == null || inputItem == null || !FurnaceSlots.isFreeFor(furnace, inputItem, expected)) {
            return TaskStatus.RUNNING;
        }
        targets.add(new Target(pos, furnace, false));
        return TaskStatus.RUNNING;
    }

    private TaskStatus loadAll(TaskContext c) {
        if (targets.isEmpty()) {
            summary = "can't reach any " + kind.noun();
            return TaskStatus.FAILED;
        }
        List<ItemStack> remainingInputs = new ArrayList<>(selected);
        int perStation = (total() + targets.size() - 1) / targets.size();
        int loadedTotal = 0;
        int stationsLoaded = 0;
        List<ItemStack> waiting = new ArrayList<>();
        for (Target target : targets) {
            List<ItemStack> share = takeUpTo(remainingInputs, perStation);
            if (share.isEmpty()) {
                break;
            }
            if (kind == Station.CAMPFIRE) {
                int loaded = placeOnCampfire(c, target, share);
                if (loaded <= 0) {
                    continue;
                }
                loadedTotal += loaded;
                stationsLoaded++;
                continue;
            }
            List<ItemStack> queue = new ArrayList<>(share);
            int loadedHere = loadOneType(c, target, queue);
            if (loadedHere < 0) {
                return TaskStatus.FAILED;
            }
            if (loadedHere <= 0) {
                remainingInputs.addAll(0, queue);
                continue;
            }
            if (waitMode) {
                waiting.addAll(queue);
            }
            loadedTotal += loadedHere;
            stationsLoaded++;
            if (!waitMode) {
                addJob(c, target, share.get(0), loadedHere);
            }
        }
        if (loadedTotal == 0) {
            summary = "couldn't load any " + kind.noun() + " with " + input.label();
            return TaskStatus.FAILED;
        }
        this.loadedCount = loadedTotal;
        this.stationsLoaded = stationsLoaded;
        this.waitingInputs.clear();
        this.waitingInputs.addAll(waiting);
        if (waitMode) {
            phase = Phase.WAIT;
            waitTicks = 0;
            collected = 0;
            return TaskStatus.RUNNING;
        }
        return finishLoadAndLeave(loadedTotal, stationsLoaded);
    }

    /**
     * Loads one type at a time: a furnace input slot accepts a single item type, so anything else in the queue
     * waits for the next round. Returns the number loaded, or -1 when no allowed fuel is left.
     */
    private int loadOneType(TaskContext c, Target target, List<ItemStack> queue) {
        if (queue.isEmpty()) {
            return 0;
        }
        ItemStack first = queue.remove(0);
        int needed = first.getCount() * ticksFor(idOf(first));
        BlockState fuelState = c.level().getBlockState(target.pos());
        boolean lit = fuelState.hasProperty(AbstractFurnaceBlock.LIT) && fuelState.getValue(AbstractFurnaceBlock.LIT);
        if (target.furnace().getItem(1).isEmpty() && !lit) {
            FuelPolicy.FuelPick pick = FuelPolicy.pick(c.companion(), c.level(), target.pos(), needed, fuel);
            if (pick.stacks().isEmpty()) {
                summary = fuel == null ? "I have no fuel I'm allowed to burn" : "I don't have " + fuel.label();
                return -1;
            }
            int covered = 0;
            int perItem = ticksFor(idOf(first));
            for (ItemStack fuelStack : pick.stacks()) {
                int placed = FurnaceSlots.loadFuel(target.furnace(), fuelStack);
                takeFromInventory(c.companion(), fuelStack, placed);
                fuelsUsed.add(idOf(fuelStack));
                covered += placed * burnTicksOf(c, target, fuelStack);
            }
            int enoughFor = covered / Math.max(1, perItem);
            if (enoughFor < first.getCount()) {
                fuelNote = "only enough fuel for " + Math.max(0, enoughFor) + " of " + first.getCount();
            }
        }
        int loaded = FurnaceSlots.load(target.furnace(), first, ItemStack.EMPTY);
        takeFromInventory(c.companion(), first, loaded);
        if (loaded < first.getCount()) {
            queue.add(0, first.copyWithCount(first.getCount() - loaded));
        }
        return loaded;
    }

    private int placeOnCampfire(TaskContext c, Target target, List<ItemStack> share) {
        if (!(c.level().getBlockEntity(target.pos()) instanceof CampfireBlockEntity campfire)) {
            return 0;
        }
        int loaded = 0;
        for (ItemStack stack : share) {
            for (int i = 0; i < stack.getCount(); i++) {
                if (!campfire.placeFood(c.level(), c.companion(), new ItemStack(stack.getItem()))) {
                    break;
                }
                takeFromInventory(c.companion(), stack, 1);
                loaded++;
            }
        }
        return loaded;
    }

    private void addJob(TaskContext c, Target target, ItemStack firstOfShare, int loaded) {
        String inputId = idOf(firstOfShare);
        Recipe recipe = recipeOn(kind, inputId);
        String output = recipe == null ? inputId : recipe.output();
        c.companion()
                .furnaceJobs()
                .add(new FurnaceJob(target.pos(), kind, inputId, output, loaded, target.placedByMe()));
    }

    private TaskStatus finishLoadAndLeave(int loadedTotal, int stations) {
        int estimate = estimate(kind, loadedTotal, stations);
        StringBuilder text = new StringBuilder("loaded ")
                .append(stations)
                .append(' ')
                .append(plural(stations))
                .append(" with ")
                .append(loadedTotal)
                .append(' ')
                .append(input.label())
                .append(", ready in about ")
                .append(seconds(estimate))
                .append(" s; I'll tell you when it's done");
        if (fuelNote != null) {
            text.append("; ").append(fuelNote);
        }
        summary = text.toString();
        phase = Phase.DONE;
        return TaskStatus.SUCCESS;
    }

    private static final int WAIT_INTERVAL = 20;

    private TaskStatus tickWait(TaskContext c) {
        waitTicks++;
        int deadline = estimate(kind, loadedCount, stationsLoaded) * 3 / 2 + 600;
        if (waitTicks > deadline) {
            summary = "the " + kind.noun() + " took too long; " + collected + " " + outputName() + " collected so far";
            return TaskStatus.FAILED;
        }

        BlockPos first = targets.get(0).pos();
        Vec3 center = Vec3.atCenterOf(first);
        if (c.companion().getEyePosition().distanceTo(center) > Approach.REACH) {
            if (waitApproach == null) {
                waitApproach = new Approach(first);
            }
            if (waitApproach.tick(c.companion()) == Approach.State.MOVING) {
                return TaskStatus.RUNNING;
            }
            waitApproach = null;
        } else {
            c.companion().getLookControl().setLookAt(center.x, center.y, center.z);
        }

        if (waitTicks % WAIT_INTERVAL != 0) {
            return TaskStatus.RUNNING;
        }

        boolean allDone = true;
        for (Target target : targets) {
            if (kind == Station.CAMPFIRE) {
                // A campfire throws its results out with the vanilla pickup delay; WAIT_INTERVAL is longer than it.
                collected += c.companion().collectNearbyItems(3.0, e -> !e.hasPickUpDelay());
                if (campfireCooking(c.level(), target.pos())) {
                    allDone = false;
                }
                continue;
            }
            if (!(c.level().getBlockEntity(target.pos()) instanceof AbstractFurnaceBlockEntity furnace)) {
                summary = "the " + kind.noun() + " at " + coords(target.pos()) + " is gone; " + collected + " "
                        + outputName() + " collected so far";
                return TaskStatus.FAILED;
            }
            ItemStack result = FurnaceSlots.takeResult(furnace);
            if (!result.isEmpty()) {
                collected += result.getCount();
                InventoryOps.insertOrDrop(c.companion(), result);
                FurnaceSlots.popExperience(c.level(), furnace, center);
            }
            ItemStack input = furnace.getItem(0);
            if (input.isEmpty()) {
                if (!furnace.getItem(2).isEmpty()) {
                    allDone = false;
                    continue;
                }
                if (!waitingInputs.isEmpty()) {
                    List<ItemStack> queue = new ArrayList<>();
                    queue.add(waitingInputs.remove(0));
                    int loaded = loadOneType(c, target, queue);
                    if (loaded < 0) {
                        return TaskStatus.FAILED;
                    }
                    waitingInputs.addAll(0, queue);
                    loadedCount += loaded;
                    allDone = false;
                }
                continue;
            }
            allDone = false;
            BlockState state = c.level().getBlockState(target.pos());
            boolean lit = state.hasProperty(AbstractFurnaceBlock.LIT) && state.getValue(AbstractFurnaceBlock.LIT);
            if (!lit && furnace.getItem(1).isEmpty()) {
                int needed = input.getCount() * ticksFor(idOf(input));
                FuelPolicy.FuelPick pick = FuelPolicy.pick(c.companion(), c.level(), target.pos(), needed, fuel);
                if (pick.stacks().isEmpty()) {
                    summary = "ran out of fuel; " + collected + " " + outputName() + " collected, "
                            + input.getCount() + " " + idOf(input) + " still in the " + kind.noun() + " at "
                            + coords(target.pos());
                    return TaskStatus.FAILED;
                }
                for (ItemStack fuelStack : pick.stacks()) {
                    int placed = FurnaceSlots.loadFuel(furnace, fuelStack);
                    takeFromInventory(c.companion(), fuelStack, placed);
                    fuelsUsed.add(idOf(fuelStack));
                }
            }
        }
        if (allDone && !waitingInputs.isEmpty()) {
            allDone = false;
        }
        if (allDone) {
            takeBackFuel(c);
            waitSummaryBase = "smelted " + loadedCount + " " + input.label() + " into " + collected + " " + outputName()
                    + " in " + stationsLoaded + " " + plural(stationsLoaded);
            if (fuelNote != null) {
                waitSummaryBase += "; " + fuelNote;
            }
            planLeaving(c);
            if (takeBacks.isEmpty()) {
                finishWait();
                return TaskStatus.SUCCESS;
            }
            phase = Phase.LEAVING;
            takeBackIndex = 0;
            return TaskStatus.RUNNING;
        }
        return TaskStatus.RUNNING;
    }

    /** Applies the station policy to the furnaces this task placed, after the job is done. */
    private void planLeaving(TaskContext c) {
        for (Target target : targets) {
            if (!target.placedByMe()) {
                continue;
            }
            BlockPos pos = target.pos();
            BlockState state = c.level().getBlockState(pos);
            if (!isStationBlock(state, kind)) {
                continue;
            }
            boolean base = policy == CraftTask.StationPolicy.AUTO && Stations.looksLikeBase(c.companion(), pos);
            if (policy == CraftTask.StationPolicy.KEEP || base) {
                stationNotes.add("placed a " + kind.noun() + " at " + coords(pos) + " and left it there"
                        + (base ? " (looks like a base)" : ""));
            } else if (!hasPickaxe(c.companion(), state)) {
                stationNotes.add("placed a " + kind.noun() + " at " + coords(pos)
                        + " but couldn't take it back: I have no pickaxe to pick it up");
            } else {
                takeBacks.add(pos);
            }
        }
    }

    private TaskStatus tickLeaving(TaskContext c) {
        if (settle != null) {
            if (!settle.tick(c)) {
                return TaskStatus.RUNNING;
            }
            settle = null;
            takeBackIndex++;
            return TaskStatus.RUNNING;
        }
        if (takeBackIndex >= takeBacks.size()) {
            finishWait();
            return TaskStatus.SUCCESS;
        }
        BlockPos pos = takeBacks.get(takeBackIndex);
        if (breaker == null) {
            breaker = new BlockBreaker(pos, state -> isStationBlock(state, kind));
        }
        BlockBreaker.State state = breaker.tick(c);
        if (state == BlockBreaker.State.WORKING) {
            return TaskStatus.RUNNING;
        }
        breaker = null;
        if (state == BlockBreaker.State.BROKEN || state == BlockBreaker.State.GONE) {
            stationNotes.add("placed a " + kind.noun() + " at " + coords(pos) + " and took it back");
            if (state == BlockBreaker.State.BROKEN) {
                // The drop lands after the vanilla pickup delay: take it before moving on.
                settle = new DropSettle(SETTLE_TICKS, DropSettle.INTERACT_RADIUS, null);
                return TaskStatus.RUNNING;
            }
        } else {
            stationNotes.add(
                    "placed a " + kind.noun() + " at " + coords(pos) + " but couldn't take it back: " + reason(state));
        }
        takeBackIndex++;
        return TaskStatus.RUNNING;
    }

    private void finishWait() {
        StringBuilder text = new StringBuilder(waitSummaryBase);
        for (String note : stationNotes) {
            text.append("; ").append(note);
        }
        summary = text.toString();
        phase = Phase.DONE;
    }

    private static String reason(BlockBreaker.State state) {
        return switch (state) {
            case PROTECTED -> "it is protected";
            case UNREACHABLE -> "can't reach it";
            default -> "the block is gone";
        };
    }

    /** True when a carried tool can mine the block with drops. */
    static boolean hasPickaxe(CompanionEntity companion, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        SimpleContainer inventory = companion.inventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return companion.getMainHandItem().isCorrectToolForDrops(state);
    }

    private static boolean campfireCooking(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof CampfireBlockEntity campfire)) {
            return false;
        }
        for (ItemStack stack : campfire.getItems()) {
            if (!stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void takeBackFuel(TaskContext c) {
        for (Target target : targets) {
            if (target.furnace() == null) {
                continue;
            }
            ItemStack slot = target.furnace().getItem(1);
            if (slot.isEmpty()) {
                continue;
            }
            String id = idOf(slot);
            if (!fuelsUsed.contains(id) && !slot.is(Items.BUCKET)) {
                continue;
            }
            ItemStack taken = target.furnace().removeItem(1, slot.getCount());
            InventoryOps.insertOrDrop(c.companion(), taken);
        }
    }

    private int burnTicksOf(TaskContext c, Target target, ItemStack stack) {
        return FuelPolicy.burnTicks(c.level(), target.pos(), stack.copyWithCount(1));
    }

    private int ticksFor(String itemId) {
        Recipe recipe = recipeOn(kind, itemId);
        return recipe == null ? cookTicks : recipe.cookTicks();
    }

    private String outputName() {
        return outputId == null ? input.label() : outputId;
    }

    // ---------------------------------------------------------------- small helpers

    private String plural(int amount) {
        return amount == 1 ? kind.noun() : kind.noun() + "s";
    }

    private static String seconds(int ticks) {
        if (ticks % 20 == 0) {
            return Integer.toString(ticks / 20);
        }
        return String.format(Locale.ROOT, "%.1f", ticks / 20.0);
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static List<ItemStack> takeUpTo(List<ItemStack> pending, int budget) {
        List<ItemStack> share = new ArrayList<>();
        int left = budget;
        for (int i = 0; i < pending.size() && left > 0; i++) {
            ItemStack stack = pending.get(i);
            int take = Math.min(left, stack.getCount());
            share.add(stack.copyWithCount(take));
            stack.shrink(take);
            left -= take;
            if (stack.isEmpty()) {
                pending.remove(i);
                i--;
            }
        }
        return share;
    }

    private static int takeFromInventory(CompanionEntity companion, ItemStack wanted, int max) {
        int left = max;
        SimpleContainer inventory = companion.inventory();
        for (int slot = 0; slot < inventory.getContainerSize() && left > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, wanted)) {
                continue;
            }
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            left -= take;
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
        inventory.setChanged();
        return max - left;
    }
}
