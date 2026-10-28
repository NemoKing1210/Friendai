package dev.friendai.task.farming;

import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.debug.WorkCell;
import dev.friendai.debug.WorkKind;
import dev.friendai.debug.WorkSet;
import dev.friendai.debug.WorkStatus;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.farming.CropBook;
import dev.friendai.features.farming.FarmGoal;
import dev.friendai.features.farming.FarmPlan;
import dev.friendai.features.farming.FarmPlanner;
import dev.friendai.features.farming.FarmReport;
import dev.friendai.features.farming.FarmStep;
import dev.friendai.features.farming.FieldCell;
import dev.friendai.features.navigation.minecraft.nav.Approach;
import dev.friendai.task.TaskContext;
import dev.friendai.task.inventory.DropSettle;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.mining.BlockBreaker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealSource;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * One pass over a farm: the planner decides what to harvest, replant, sow, till and bone meal, and this class walks
 * those steps tick by tick. A step whose precondition no longer holds (a crop was broken, the soil changed) marks its
 * cell and asks for a fresh plan, a few times at most. A tickable unit used by both the one-shot {@code farm} task
 * and the open-ended {@code tend_farm}.
 */
public final class FarmPass {
    private enum Step {
        BUSY,
        IDLE
    }

    /** Fresh plans allowed after the first one, whether the world disagreed or the queue ran dry. */
    private static final int MAX_REPLANS = 3;

    private static final int BONEMEAL_CAP = 8;
    /** How long a harvest's drops get to land and leave their pickup delay before the pass moves on. */
    private static final int SETTLE_TICKS = 12;

    private static final ItemMatcher BERRIES =
            ItemMatcher.parse("minecraft:sweet_berries").orElseThrow();

    private final BlockPos center;
    private final int radius;
    private final @Nullable Item crop;
    private final boolean till;
    private final boolean boneMeal;

    private final Map<String, Integer> harvested = new LinkedHashMap<>();
    private final Set<BlockPos> attempted = new HashSet<>();
    /** Cells whose harvest was skipped for lack of room, so their replant is skipped too. */
    private final Set<BlockPos> skippedHarvest = new HashSet<>();
    /** Cells that could not be tilled, so the sow planned after the till is skipped. */
    private final Set<BlockPos> failedTill = new HashSet<>();

    private final Deque<FarmStep> queue = new ArrayDeque<>();

    private CropBook book;
    private boolean planned;
    private boolean replanRequested;
    private boolean workedSincePlan;
    private int replans;
    private int lastUnsown;
    private List<FarmStep.NeedSeeds> shortfall = List.of();
    private FarmStep current;

    private BlockBreaker breaker;
    private BlockPos breakerPos;
    private Map<String, Integer> breakerDrops;
    private Approach berryApproach;
    private BlockPos berryPos;
    /** The wait for a just-harvested block's drops, if the pass is in one. */
    private DropSettle settle;
    /** What the companion carried before picking berries, and where, to count the gain when the berries land. */
    private int berryBefore;

    private BlockPos berrySettling;

    private Approach approach;
    private int meals;

    private int replanted;
    private int planted;
    private int tilled;
    private int noSeeds;
    private boolean noHoe;
    private boolean stopped;
    private boolean done;
    private FarmReport report;

    public FarmPass(BlockPos center, int radius, @Nullable Item crop, boolean till) {
        this(center, radius, crop, till, false);
    }

    public FarmPass(BlockPos center, int radius, @Nullable Item crop, boolean till, boolean boneMeal) {
        this.center = center.immutable();
        this.radius = Math.max(1, Math.min(16, radius));
        this.crop = crop;
        this.till = till;
        this.boneMeal = boneMeal;
    }

    /** Works a little each tick; true when the pass is over. */
    public boolean tick(TaskContext c) {
        if (stopped || done) {
            return true;
        }
        while (true) {
            if (current == null && !nextStep(c)) {
                finish();
                done = true;
                return true;
            }
            if (run(c, current) == Step.BUSY) {
                return false;
            }
            current = null;
            workedSincePlan = true;
        }
    }

    public FarmReport report() {
        if (report == null) {
            finish();
        }
        return report;
    }

    /** The concrete block currently being worked, if the plan has selected one. */
    public List<DebugTarget> debugTargets() {
        if (current == null) {
            return List.of();
        }
        DebugAction action;
        String detail;
        if (current instanceof FarmStep.Harvest) {
            action = DebugAction.HARVEST;
            detail = "Harvest crop";
        } else if (current instanceof FarmStep.Replant) {
            action = DebugAction.PLACE;
            detail = "Replant crop";
        } else if (current instanceof FarmStep.Sow) {
            action = DebugAction.PLACE;
            detail = "Sow crop";
        } else if (current instanceof FarmStep.Till) {
            action = DebugAction.USE;
            detail = "Till soil";
        } else if (current instanceof FarmStep.Bonemeal) {
            action = DebugAction.USE;
            detail = "Use bone meal";
        } else {
            return List.of();
        }
        BlockPos pos = at(currentCell(current));
        return List.of(new DebugTarget(pos.getX(), pos.getY(), pos.getZ(), action, detail));
    }

    /** Returns the remaining planned plot steps, with completed/failed cells summarized from retained pass state. */
    public List<WorkSet> debugWorkSets() {
        List<WorkCell> cells = new ArrayList<>();
        if (current != null && !(current instanceof FarmStep.NeedSeeds)) {
            cells.add(workCell(current, WorkStatus.NEXT));
        }
        for (FarmStep step : queue) {
            if (!(step instanceof FarmStep.NeedSeeds)) cells.add(workCell(step, WorkStatus.PENDING));
        }
        if (cells.isEmpty() && attempted.isEmpty()) return List.of();
        int skipped = skippedHarvest.size() + failedTill.size();
        int completed = replanted
                + planted
                + tilled
                + harvested.values().stream().mapToInt(Integer::intValue).sum();
        int next = (int)
                cells.stream().filter(cell -> cell.status() == WorkStatus.NEXT).count();
        int pending = (int) cells.stream()
                .filter(cell -> cell.status() == WorkStatus.PENDING)
                .count();
        int total = Math.max(completed + skipped + next + pending, completed + skipped + cells.size());
        return List.of(new WorkSet(
                crop == null ? "Farm plot" : "Farm " + crop.toString(),
                WorkKind.PLOT,
                total,
                completed,
                next,
                Math.max(pending, total - completed - skipped - next),
                skipped,
                false,
                cells));
    }

    private WorkCell workCell(FarmStep step, WorkStatus status) {
        DebugAction action;
        String detail;
        if (step instanceof FarmStep.Harvest) {
            action = DebugAction.HARVEST;
            detail = "Harvest crop";
        } else if (step instanceof FarmStep.Replant) {
            action = DebugAction.PLACE;
            detail = "Replant crop";
        } else if (step instanceof FarmStep.Sow) {
            action = DebugAction.PLACE;
            detail = "Sow crop";
        } else if (step instanceof FarmStep.Till) {
            action = DebugAction.USE;
            detail = "Till soil";
        } else {
            action = DebugAction.USE;
            detail = "Use bone meal";
        }
        BlockPos pos = at(currentCell(step));
        return new WorkCell(pos.getX(), pos.getY(), pos.getZ(), action, status, detail);
    }

    private static FieldCell currentCell(FarmStep step) {
        return switch (step) {
            case FarmStep.Harvest value -> value.cell();
            case FarmStep.Replant value -> value.cell();
            case FarmStep.Sow value -> value.cell();
            case FarmStep.Till value -> value.cell();
            case FarmStep.Bonemeal value -> value.cell();
            case FarmStep.NeedSeeds ignored -> throw new IllegalArgumentException("seed shortfall is not a block step");
        };
    }

    /** Stops navigation and any block breaking; the pass is over. */
    public void stop(TaskContext c) {
        stopped = true;
        settle = null;
        if (breaker != null) {
            breaker.stop(c);
            breaker = null;
        }
        c.companion().getNavigation().stop();
    }

    /** Takes the next step off the queue, planning first when there is no plan or the world disagreed. */
    private boolean nextStep(TaskContext c) {
        boolean wanted = !planned
                || (replanRequested && replans < MAX_REPLANS)
                || (queue.isEmpty() && workedSincePlan && replans < MAX_REPLANS);
        if (wanted) {
            if (planned) {
                replans++;
            }
            plan(c);
        }
        current = queue.poll();
        return current != null;
    }

    private void plan(TaskContext c) {
        if (book == null) {
            book = CropIndex.of(c.level());
        }
        FarmPlan plan = FarmPlanner.plan(
                FarmSnapshotBuilder.build(c, center, radius, attempted),
                new FarmGoal(crop == null ? null : itemId(crop), till, boneMeal, 0, FarmGoal.DEFAULT_MAX_STEPS),
                book);
        queue.clear();
        List<FarmStep.NeedSeeds> needs = new ArrayList<>();
        for (FarmStep step : plan.steps()) {
            if (step instanceof FarmStep.NeedSeeds need) {
                needs.add(need);
            } else {
                queue.add(step);
            }
        }
        shortfall = needs;
        lastUnsown = plan.unsown();
        noHoe |= plan.notes().contains("missing hoe");
        planned = true;
        replanRequested = false;
        workedSincePlan = false;
    }

    private Step run(TaskContext c, FarmStep step) {
        return switch (step) {
            case FarmStep.Harvest harvest -> harvestTick(c, at(harvest.cell()));
            case FarmStep.Replant replant -> replantTick(c, at(replant.cell()), itemOf(replant.seed()));
            case FarmStep.Sow sow -> sowTick(c, at(sow.cell()), itemOf(sow.seed()));
            case FarmStep.Till tillStep -> tillTick(c, at(tillStep.cell()));
            case FarmStep.Bonemeal bonemeal -> bonemealTick(c, at(bonemeal.cell()));
            case FarmStep.NeedSeeds need -> Step.IDLE;
        };
    }

    private BlockPos at(FieldCell cell) {
        return center.offset(cell.dx(), cell.dy(), cell.dz()).immutable();
    }

    /** The world disagreed with the plan at this cell: leave it out of later plans and plan again. */
    private void fail(BlockPos pos) {
        attempted.add(pos);
        replanRequested = true;
    }

    private Step harvestTick(TaskContext c, BlockPos pos) {
        if (settle != null) {
            if (!settle.tick(c)) {
                return Step.BUSY;
            }
            settle = null;
            if (berrySettling != null) {
                int gained = c.companion().inventory().countItem(Items.SWEET_BERRIES) - berryBefore;
                if (gained > 0) {
                    harvested.merge(itemId(Items.SWEET_BERRIES), gained, Integer::sum);
                }
                attempted.add(berrySettling);
                berrySettling = null;
            }
            return Step.IDLE;
        }
        if (breaker != null) {
            BlockBreaker.State state = breaker.tick(c);
            if (state == BlockBreaker.State.WORKING) {
                return Step.BUSY;
            }
            if (state == BlockBreaker.State.BROKEN && breakerDrops != null) {
                for (Map.Entry<String, Integer> entry : breakerDrops.entrySet()) {
                    harvested.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }
            }
            breaker.stop(c);
            attempted.add(breakerPos);
            breaker = null;
            breakerPos = null;
            breakerDrops = null;
            // The crop's drops lie where it stood: wait for them, so replanting finds the seeds.
            settle = new DropSettle(SETTLE_TICKS, DropSettle.INTERACT_RADIUS, null);
            return Step.BUSY;
        }
        if (berryPos != null) {
            Approach.State state = berryApproach.tick(c);
            if (state == Approach.State.MOVING) {
                return Step.BUSY;
            }
            BlockPos berries = berryPos;
            berryPos = null;
            berryApproach = null;
            if (state == Approach.State.IN_REACH && c.companion().hands().canInteract(berries)) {
                berryBefore = c.companion().inventory().countItem(Items.SWEET_BERRIES);
                berrySettling = berries;
                c.companion().hands().use(berries);
                settle = new DropSettle(SETTLE_TICKS, DropSettle.INTERACT_RADIUS, BERRIES);
                return Step.BUSY;
            }
            fail(berries);
            return Step.IDLE;
        }
        if (!hasRoom(c.companion())) {
            skippedHarvest.add(pos);
            attempted.add(pos);
            return Step.IDLE;
        }
        Optional<Harvestable> found = Crops.harvestable(c.level(), pos);
        if (found.isEmpty()) {
            fail(pos);
            return Step.IDLE;
        }
        if (found.get().kind() == Harvestable.Kind.BERRIES) {
            berryPos = pos;
            berryApproach = new Approach(pos);
            return Step.BUSY;
        }
        BlockState state = c.level().getBlockState(pos);
        breakerDrops = countDrops(c.level(), pos, state, c.companion());
        breaker = new BlockBreaker(
                pos, current -> Crops.harvestable(c.level(), pos).isPresent());
        breakerPos = pos;
        return Step.BUSY;
    }

    private Step replantTick(TaskContext c, BlockPos pos, Item seedItem) {
        BlockPos below = pos.below();
        if (approach == null) {
            if (skippedHarvest.contains(pos)) {
                return Step.IDLE;
            }
            approach = new Approach(below);
        }
        Approach.State state = approach.tick(c);
        if (state == Approach.State.MOVING) {
            return Step.BUSY;
        }
        if (state == Approach.State.IN_REACH
                && c.companion().hands().canInteract(below)
                && c.level().getBlockState(pos).isAir()
                && Crops.isReplantSurface(c.level().getBlockState(below), seedItem)) {
            ItemStack seed = takeOne(c.companion(), seedItem);
            if (seed.isEmpty()) {
                noSeeds++;
                fail(pos);
            } else {
                InteractionResult result = c.companion().hands().place(seed, below, Direction.UP);
                if (result.consumesAction() && Crops.isCrop(c.level().getBlockState(pos))) {
                    replanted++;
                }
            }
        } else {
            fail(pos);
        }
        attempted.add(pos);
        clearAction();
        return Step.IDLE;
    }

    private Step sowTick(TaskContext c, BlockPos pos, Item seedItem) {
        if (approach == null) {
            if (failedTill.contains(pos)) {
                return Step.IDLE;
            }
            approach = new Approach(pos);
        }
        Approach.State state = approach.tick(c);
        if (state == Approach.State.MOVING) {
            return Step.BUSY;
        }
        if (state == Approach.State.IN_REACH
                && c.companion().hands().canInteract(pos)
                && Crops.isReplantSurface(c.level().getBlockState(pos), seedItem)
                && c.level().getBlockState(pos.above()).isAir()) {
            ItemStack seed = takeOne(c.companion(), seedItem);
            if (seed.isEmpty()) {
                noSeeds++;
                replanRequested = true;
            } else {
                InteractionResult result = c.companion().hands().place(seed, pos, Direction.UP);
                if (result.consumesAction() && Crops.isCrop(c.level().getBlockState(pos.above()))) {
                    planted++;
                }
            }
        } else {
            fail(pos);
        }
        attempted.add(pos);
        clearAction();
        return Step.IDLE;
    }

    private Step tillTick(TaskContext c, BlockPos pos) {
        if (approach == null) {
            approach = new Approach(pos);
        }
        Approach.State state = approach.tick(c);
        if (state == Approach.State.MOVING) {
            return Step.BUSY;
        }
        if (state == Approach.State.IN_REACH
                && c.companion().hands().canInteract(pos)
                && FarmSnapshotBuilder.isTillCandidate(c.level(), pos)) {
            ItemStack hoe = takeHoe(c.companion());
            if (hoe.isEmpty()) {
                noHoe = true;
                failedTill.add(pos);
                fail(pos);
                clearAction();
                return Step.IDLE;
            }
            c.companion().hands().place(hoe, pos, Direction.UP);
            if (c.level().getBlockState(pos).is(Blocks.COARSE_DIRT)) {
                ItemStack again = takeHoe(c.companion());
                if (!again.isEmpty()) {
                    c.companion().hands().place(again, pos, Direction.UP);
                }
            }
            if (c.level().getBlockState(pos).is(Blocks.FARMLAND)) {
                tilled++;
            } else {
                failedTill.add(pos);
                fail(pos);
            }
        } else {
            failedTill.add(pos);
            fail(pos);
        }
        attempted.add(pos);
        clearAction();
        return Step.IDLE;
    }

    private Step bonemealTick(TaskContext c, BlockPos pos) {
        if (approach == null) {
            if (!growing(c.level(), pos)) {
                fail(pos);
                return Step.IDLE;
            }
            approach = new Approach(pos);
            meals = 0;
        }
        Approach.State state = approach.tick(c);
        if (state == Approach.State.MOVING) {
            return Step.BUSY;
        }
        if (state != Approach.State.IN_REACH || !c.companion().hands().canInteract(pos)) {
            fail(pos);
            clearAction();
            return Step.IDLE;
        }
        if (!growing(c.level(), pos) || meals >= BONEMEAL_CAP) {
            attempted.add(pos);
            clearAction();
            return Step.IDLE;
        }
        ItemStack meal = takeOne(c.companion(), Items.BONE_MEAL);
        if (meal.isEmpty()) {
            fail(pos);
            clearAction();
            return Step.IDLE;
        }
        c.companion().hands().place(meal, pos, Direction.UP);
        meals++;
        return Step.BUSY;
    }

    private static boolean growing(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return Crops.isCrop(state)
                && state.getBlock() instanceof BonemealableBlock bonemealable
                && bonemealable.isValidBonemealTarget(level, pos, state, BonemealSource.INTERACTION);
    }

    private static boolean hasRoom(CompanionEntity companion) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            if (companion.inventory().getItem(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack takeHoe(CompanionEntity companion) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.is(ItemTags.HOES)) {
                companion.inventory().setItem(slot, ItemStack.EMPTY);
                companion.inventory().setChanged();
                return stack;
            }
        }
        ItemStack hand = companion.getMainHandItem();
        if (hand.is(ItemTags.HOES)) {
            companion.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            return hand;
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack takeOne(CompanionEntity companion, Item item) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.is(item)) {
                ItemStack one = stack.copyWithCount(1);
                stack.shrink(1);
                if (stack.isEmpty()) {
                    companion.inventory().setItem(slot, ItemStack.EMPTY);
                }
                companion.inventory().setChanged();
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    private static Map<String, Integer> countDrops(
            ServerLevel level, BlockPos pos, BlockState state, CompanionEntity companion) {
        Map<String, Integer> drops = new LinkedHashMap<>();
        for (ItemStack stack : net.minecraft.world.level.block.Block.getDrops(
                state, level, pos, level.getBlockEntity(pos), companion, ItemStack.EMPTY)) {
            drops.merge(itemId(stack.getItem()), stack.getCount(), Integer::sum);
        }
        return drops;
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static Item itemOf(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private void clearAction() {
        approach = null;
    }

    private void finish() {
        if (report == null) {
            Map<String, Integer> missing = new LinkedHashMap<>();
            for (FarmStep.NeedSeeds need : shortfall) {
                missing.merge(need.item(), need.count(), Integer::sum);
            }
            report = new FarmReport(
                    new LinkedHashMap<>(harvested),
                    replanted,
                    planted,
                    tilled,
                    noSeeds + lastUnsown,
                    noHoe,
                    missing,
                    lastUnsown);
        }
    }
}
