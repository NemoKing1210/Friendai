package dev.friendai.features.cooking.minecraft.task;

import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.cooking.minecraft.FuelPolicy;
import dev.friendai.features.cooking.minecraft.FurnaceJob;
import dev.friendai.features.cooking.minecraft.FurnaceSlots;
import dev.friendai.features.crafting.Recipe;
import dev.friendai.features.crafting.Station;
import dev.friendai.features.crafting.minecraft.RecipeIndex;
import dev.friendai.features.crafting.minecraft.Stations;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.world.block.BlockBreaker;
import dev.friendai.world.movement.Approach;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Collects finished items from the furnaces this companion loaded, or from one given furnace. Only the result
 * slot is touched on somebody else's furnace; leftover fuel and station take-back belong to our own jobs.
 */
public final class CollectSmeltedTask implements Task {
    private record Target(
            BlockPos pos, Station station, @Nullable FurnaceJob job) {}

    private enum Phase {
        APPROACH,
        TAKE,
        BREAK,
        WAIT
    }

    /** How often a wait rechecks the loaded furnaces. */
    private static final int WAIT_INTERVAL = 20;

    /** Extra ticks on top of the computed cooking time before a wait gives up and names the blocker. */
    private static final int WAIT_GRACE_TICKS = 400;

    /** Cooking time assumed for a furnace whose contents cannot be read, so an out-of-sight wait still ends. */
    private static final int UNSEEN_COOK_TICKS = 200;

    private final @Nullable BlockPos target;
    private final List<Target> targets = new ArrayList<>();
    private final Set<BlockPos> settled = new LinkedHashSet<>();
    private final Map<String, Integer> collected = new LinkedHashMap<>();
    private final Set<Station> stationsUsed = new LinkedHashSet<>();
    private final List<String> notes = new ArrayList<>();

    private String failure;
    private String summary = "";
    private int index;
    private Phase phase = Phase.APPROACH;
    private Approach approach;
    /** Each station counted once, however many times a wait rechecks it. */
    private final Set<BlockPos> collectedPositions = new LinkedHashSet<>();

    private BlockPos breakPos;
    private BlockBreaker breaker;
    /** A loaded furnace that is still cooking: the collection waits for it instead of finishing empty. */
    private boolean pendingCooking;
    /** What the wait is currently about, replaced on every sweep rather than accumulated. */
    private String waitingNote;

    private int waitTicks;
    private int waitLimit;
    /** The first furnace of this task that cannot produce its output any more. */
    private String blocked;

    public CollectSmeltedTask(@Nullable BlockPos target) {
        this.target = target;
    }

    @Override
    public String type() {
        return "collect_smelted";
    }

    /** The wait has its own deadline computed from the remaining cooking time, so the generic timeout is off. */
    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public void start(TaskContext c) {
        try {
            if (target != null) {
                Station station = stationOf(c.level().getBlockState(target));
                if (station != null) {
                    targets.add(new Target(target, station, jobAt(c.companion(), target)));
                }
            } else {
                List<FurnaceJob> jobs = c.companion().furnaceJobs().all();
                if (!jobs.isEmpty()) {
                    for (FurnaceJob job : jobs) {
                        targets.add(new Target(job.pos(), job.station(), job));
                    }
                } else {
                    nearestWithResult(c)
                            .ifPresent(pos -> targets.add(
                                    new Target(pos, stationOf(c.level().getBlockState(pos)), null)));
                }
            }
            if (targets.isEmpty()) {
                failure = "no furnace with finished items nearby";
            }
        } catch (RuntimeException e) {
            failure = "no furnace with finished items nearby";
        }
    }

    private static FurnaceJob jobAt(CompanionEntity companion, BlockPos pos) {
        String dimension = companion.level().dimension().identifier().toString();
        for (FurnaceJob job : companion.furnaceJobs().all()) {
            if (job.pos().equals(pos) && job.dimension().equals(dimension)) {
                return job;
            }
        }
        return null;
    }

    private Optional<BlockPos> nearestWithResult(TaskContext c) {
        BlockPos origin = c.companion().blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Station station : List.of(Station.FURNACE, Station.SMOKER, Station.BLAST_FURNACE)) {
            for (BlockPos pos : Stations.nearbyAll(c.companion(), station, 8)) {
                if (!(c.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)
                        || furnace.getItem(2).isEmpty()) {
                    continue;
                }
                double distance = origin.distSqr(pos);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = pos;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (failure != null) {
            summary = failure;
            return TaskStatus.FAILED;
        }
        if (phase == Phase.WAIT) {
            return tickWait(c);
        }
        if (index >= targets.size()) {
            return finish(c);
        }
        Target current = targets.get(index);
        return switch (phase) {
            case APPROACH -> approach(c, current);
            case TAKE -> take(c, current);
            case BREAK -> tickBreak(c);
            case WAIT -> tickWait(c);
        };
    }

    private TaskStatus approach(TaskContext c, Target current) {
        if (approach == null) {
            approach = new Approach(current.pos());
        }
        Approach.State state = approach.tick(c);
        if (state == Approach.State.MOVING) {
            return TaskStatus.RUNNING;
        }
        approach = null;
        if (state == Approach.State.UNREACHABLE || !c.companion().hands().canInteract(current.pos())) {
            noteUnreachable(c, current);
            nextTarget();
            return TaskStatus.RUNNING;
        }
        phase = Phase.TAKE;
        return TaskStatus.RUNNING;
    }

    private TaskStatus take(TaskContext c, Target current) {
        BlockPos pos = current.pos();
        if (!(c.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
            if (current.job() != null) {
                noteMissing(c, current);
            }
            nextTarget();
            return TaskStatus.RUNNING;
        }
        ItemStack result = FurnaceSlots.takeResult(furnace);
        if (!result.isEmpty()) {
            var output = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
            collected.merge(output, result.getCount(), Integer::sum);
            InventoryOps.insertOrDrop(c.companion(), result);
            FurnaceSlots.popExperience(c.level(), furnace, Vec3.atCenterOf(pos));
            stationsUsed.add(current.station());
            collectedPositions.add(pos);
        }
        FurnaceJob job = settled.contains(pos) ? null : current.job();
        if (job != null) {
            ItemStack input = furnace.getItem(0);
            if (input.isEmpty()) {
                settled.add(pos);
                takeFuel(c, furnace);
                c.companion().furnaceJobs().remove(pos);
                decideStation(c, current);
            } else if (outOfFuel(c, pos, furnace)) {
                blockedOnce("the " + noun(current) + " at " + coords(pos) + " stopped: out of fuel, " + input.getCount()
                        + " " + idOf(input) + " left");
            } else {
                pendingCooking = true;
                waitingNote = "the " + noun(current) + " at " + coords(pos) + " is still working (" + input.getCount()
                        + " " + idOf(input) + " left)";
            }
        }
        if (breakPos != null) {
            phase = Phase.BREAK;
            return TaskStatus.RUNNING;
        }
        nextTarget();
        return TaskStatus.RUNNING;
    }

    /** A known job whose furnace is not visible: another dimension is a blocker, an unloaded chunk is a wait. */
    private void noteMissing(TaskContext c, Target current) {
        FurnaceJob job = current.job();
        if (job.station() == Station.CAMPFIRE) {
            // A campfire drops its food instead of keeping a result slot; this task only reads furnace results.
            return;
        }
        String pos = coords(current.pos());
        if (!c.level().dimension().identifier().toString().equals(job.dimension())) {
            blockedOnce("the " + noun(current) + " at " + pos + " is in another dimension; its job for " + job.output()
                    + " was not collected");
            return;
        }
        if (!c.level().hasChunkAt(current.pos())) {
            pendingCooking = true;
            waitingNote = "the " + noun(current) + " at " + pos + " is out of sight; " + job.output()
                    + " is not collected yet";
            return;
        }
        blockedOnce(
                "the " + noun(current) + " at " + pos + " is gone; its job for " + job.output() + " was not collected");
    }

    /** A known job this task cannot walk to: a named blocker, unless the chunk or the dimension hides it. */
    private void noteUnreachable(TaskContext c, Target current) {
        FurnaceJob job = current.job();
        if (job == null || job.station() == Station.CAMPFIRE) {
            return;
        }
        String pos = coords(current.pos());
        if (!c.level().dimension().identifier().toString().equals(job.dimension())) {
            blockedOnce("the " + noun(current) + " at " + pos + " is in another dimension; its job for " + job.output()
                    + " was not collected");
            return;
        }
        if (!c.level().hasChunkAt(current.pos())) {
            pendingCooking = true;
            waitingNote = "the " + noun(current) + " at " + pos + " is out of sight; " + job.output()
                    + " is not collected yet";
            return;
        }
        blockedOnce("couldn't reach the " + noun(current) + " at " + pos + "; its job for " + job.output()
                + " was not collected");
    }

    private static boolean outOfFuel(TaskContext c, BlockPos pos, AbstractFurnaceBlockEntity furnace) {
        BlockState state = c.level().getBlockState(pos);
        boolean lit = state.hasProperty(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT)
                && state.getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT);
        return !lit && furnace.getItem(1).isEmpty();
    }

    private void blockedOnce(String reason) {
        if (blocked == null) {
            blocked = reason;
        }
    }

    private static String noun(Target target) {
        return target.station() == null ? "furnace" : target.station().noun();
    }

    private static void takeFuel(TaskContext c, AbstractFurnaceBlockEntity furnace) {
        ItemStack slot = furnace.getItem(1);
        if (slot.isEmpty()) {
            return;
        }
        if (!FuelPolicy.isFuel(slot) && !slot.is(Items.BUCKET)) {
            return;
        }
        ItemStack taken = furnace.removeItem(1, slot.getCount());
        InventoryOps.insertOrDrop(c.companion(), taken);
    }

    /** The station policy for a furnace this task placed: keep it, or take it back when a tool allows. */
    private void decideStation(TaskContext c, Target current) {
        FurnaceJob job = current.job();
        if (job == null || !job.placedByMe()) {
            return;
        }
        BlockState state = c.level().getBlockState(current.pos());
        Station station = current.station();
        boolean base = Stations.looksLikeBase(c.companion(), current.pos());
        if (station != Station.FURNACE && station != Station.SMOKER && station != Station.BLAST_FURNACE) {
            return;
        }
        if (base) {
            notes.add("placed a " + station.noun() + " at " + coords(current.pos())
                    + " and left it there (looks like a base)");
            return;
        }
        if (!SmeltTask.hasPickaxe(c.companion(), state)) {
            notes.add("placed a " + station.noun() + " at " + coords(current.pos())
                    + " but couldn't take it back: I have no pickaxe to pick it up");
            return;
        }
        breakPos = current.pos();
    }

    private TaskStatus tickBreak(TaskContext c) {
        if (breaker == null) {
            Station station = targets.get(index).station();
            breaker = new BlockBreaker(breakPos, state -> state.is(Stations.block(station)));
        }
        BlockBreaker.State state = breaker.tick(c);
        if (state == BlockBreaker.State.WORKING) {
            return TaskStatus.RUNNING;
        }
        String why = breaker.reason(state);
        breaker = null;
        if (state == BlockBreaker.State.BROKEN || state == BlockBreaker.State.GONE) {
            notes.add("placed a " + targets.get(index).station().noun() + " at " + coords(breakPos)
                    + " and took it back");
        } else {
            notes.add("placed a " + targets.get(index).station().noun() + " at " + coords(breakPos)
                    + " but couldn't take it back: " + why);
        }
        breakPos = null;
        nextTarget();
        return TaskStatus.RUNNING;
    }

    private void nextTarget() {
        index++;
        phase = Phase.APPROACH;
        approach = null;
    }

    private TaskStatus finish(TaskContext c) {
        if (blocked != null) {
            String collectedText = collectionSummary();
            summary = collectedText == null ? blocked : collectedText + "; " + blocked;
            return TaskStatus.FAILED;
        }
        if (pendingCooking) {
            if (waitLimit <= 0) {
                waitLimit = waitTicks + pendingCookingTicks(c) + WAIT_GRACE_TICKS;
            }
            phase = Phase.WAIT;
            return TaskStatus.RUNNING;
        }
        String collectedText = collectionSummary();
        if (collectedText == null) {
            summary = "no furnace with finished items nearby";
            return TaskStatus.FAILED;
        }
        summary = collectedText;
        return TaskStatus.SUCCESS;
    }

    /**
     * Waits for the loaded furnaces to finish. The result is either every finished item collected, or a failure that
     * names what blocked it, so a plan never advances on a collection that produced nothing.
     */
    private TaskStatus tickWait(TaskContext c) {
        waitTicks++;
        if (waitTicks >= waitLimit) {
            String collectedText = collectionSummary();
            String pendingText = waitingNote == null ? "the loaded furnaces are still cooking" : waitingNote;
            summary = collectedText == null ? pendingText + " at the deadline" : collectedText + "; " + pendingText;
            return TaskStatus.FAILED;
        }
        if (waitTicks % WAIT_INTERVAL == 0) {
            index = 0;
            phase = Phase.APPROACH;
            approach = null;
            pendingCooking = false;
            waitingNote = null;
        }
        return TaskStatus.RUNNING;
    }

    /** How long the furnaces this task loaded still need, from the items left in their input slots. */
    private int pendingCookingTicks(TaskContext c) {
        RecipeIndex index = RecipeIndex.of(c.level());
        String dimension = c.level().dimension().identifier().toString();
        int ticks = 0;
        for (Target each : targets) {
            FurnaceJob job = each.job();
            if (job == null || settled.contains(each.pos()) || !dimension.equals(job.dimension())) {
                continue;
            }
            if (!c.level().hasChunkAt(each.pos())
                    || !(c.level().getBlockEntity(each.pos()) instanceof AbstractFurnaceBlockEntity furnace)) {
                ticks += Math.max(1, job.loaded()) * UNSEEN_COOK_TICKS;
                continue;
            }
            ItemStack input = furnace.getItem(0);
            if (!input.isEmpty()) {
                ticks += input.getCount() * cookTicks(index, job, input);
            }
        }
        return ticks;
    }

    private static int cookTicks(RecipeIndex index, FurnaceJob job, ItemStack input) {
        for (Recipe recipe : index.cookingFrom(idOf(input))) {
            if (recipe.station() == job.station()) {
                return Math.max(1, recipe.cookTicks());
            }
        }
        return switch (job.station()) {
            case SMOKER, BLAST_FURNACE -> 100;
            case CAMPFIRE -> 600;
            default -> 200;
        };
    }

    /** The "collected ..." sentence, or null when this task took nothing. */
    private String collectionSummary() {
        if (collected.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder("collected ");
        String outputs = collected.entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey())
                .reduce((a, b) -> a + ", " + b)
                .orElse("0");
        int stations = collectedPositions.size();
        text.append(outputs).append(" from ").append(stations).append(' ');
        if (stationsUsed.size() == 1) {
            Station station = stationsUsed.iterator().next();
            text.append(stations == 1 ? station.noun() : station.noun() + "s");
        } else {
            text.append(stations == 1 ? "station" : "stations");
        }
        for (String note : notes) {
            text.append("; ").append(note);
        }
        return text.toString();
    }

    @Override
    public int waitingTicks() {
        return phase == Phase.WAIT ? waitTicks : -1;
    }

    @Override
    public int waitingLimitTicks() {
        return phase == Phase.WAIT ? waitLimit : 0;
    }

    private static Station stationOf(BlockState state) {
        if (state.is(Blocks.FURNACE)) {
            return Station.FURNACE;
        }
        if (state.is(Blocks.SMOKER)) {
            return Station.SMOKER;
        }
        if (state.is(Blocks.BLAST_FURNACE)) {
            return Station.BLAST_FURNACE;
        }
        return null;
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().getNavigation().stop();
        if (breaker != null) {
            breaker.stop(c);
            breaker = null;
        }
    }

    @Override
    public String describe() {
        if (phase == Phase.WAIT) {
            return "waiting for " + targets.size() + " loaded furnace" + (targets.size() == 1 ? "" : "s")
                    + " to finish";
        }
        return "collecting from " + targets.size() + " furnace" + (targets.size() == 1 ? "" : "s");
    }

    @Override
    public List<DebugTarget> debugTargets() {
        if (index >= targets.size()) {
            return List.of();
        }
        Target current = targets.get(index);
        if (phase == Phase.BREAK) {
            BlockPos pos = breaker == null ? breakPos : breaker.target();
            return pos == null
                    ? List.of()
                    : List.of(new DebugTarget(
                            pos.getX(),
                            pos.getY(),
                            pos.getZ(),
                            DebugAction.BREAK,
                            "Take back " + current.station().noun()));
        }
        return List.of(new DebugTarget(
                current.pos().getX(),
                current.pos().getY(),
                current.pos().getZ(),
                DebugAction.COLLECT,
                "Collect finished items"));
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
