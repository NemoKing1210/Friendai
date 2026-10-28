package dev.friendai.task.builtin;

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
import dev.friendai.task.crafting.Stations;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.mining.BlockBreaker;
import dev.friendai.task.nav.Approach;
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
        BREAK
    }

    private final @Nullable BlockPos target;
    private final List<Target> targets = new ArrayList<>();
    private final Map<String, Integer> collected = new LinkedHashMap<>();
    private final Set<Station> stationsUsed = new LinkedHashSet<>();
    private final List<String> notes = new ArrayList<>();

    private String failure;
    private String summary = "";
    private int index;
    private Phase phase = Phase.APPROACH;
    private Approach approach;
    private int collectedStations;
    private BlockPos breakPos;
    private BlockBreaker breaker;

    public CollectSmeltedTask(@Nullable BlockPos target) {
        this.target = target;
    }

    @Override
    public String type() {
        return "collect_smelted";
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
        for (FurnaceJob job : companion.furnaceJobs().all()) {
            if (job.pos().equals(pos)) {
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
        if (index >= targets.size()) {
            return finish();
        }
        Target current = targets.get(index);
        return switch (phase) {
            case APPROACH -> approach(c, current);
            case TAKE -> take(c, current);
            case BREAK -> tickBreak(c);
        };
    }

    private TaskStatus approach(TaskContext c, Target current) {
        if (approach == null) {
            approach = new Approach(current.pos());
        }
        Approach.State state = approach.tick(c.companion());
        if (state == Approach.State.MOVING) {
            return TaskStatus.RUNNING;
        }
        approach = null;
        if (state == Approach.State.UNREACHABLE || !c.companion().hands().canInteract(current.pos())) {
            nextTarget();
            return TaskStatus.RUNNING;
        }
        phase = Phase.TAKE;
        return TaskStatus.RUNNING;
    }

    private TaskStatus take(TaskContext c, Target current) {
        BlockPos pos = current.pos();
        if (!(c.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
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
            collectedStations++;
        }
        FurnaceJob job = current.job();
        if (job != null) {
            ItemStack input = furnace.getItem(0);
            if (input.isEmpty()) {
                takeFuel(c, furnace);
                c.companion().furnaceJobs().remove(pos);
                decideStation(c, current);
            } else {
                notes.add("the " + current.station().noun() + " at " + coords(pos) + " is still working ("
                        + input.getCount() + " " + idOf(input) + " left)");
            }
        }
        if (breakPos != null) {
            phase = Phase.BREAK;
            return TaskStatus.RUNNING;
        }
        nextTarget();
        return TaskStatus.RUNNING;
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
        breaker = null;
        if (state == BlockBreaker.State.BROKEN || state == BlockBreaker.State.GONE) {
            notes.add("placed a " + targets.get(index).station().noun() + " at " + coords(breakPos)
                    + " and took it back");
        } else {
            notes.add("placed a " + targets.get(index).station().noun() + " at " + coords(breakPos)
                    + " but couldn't take it back: " + reason(state));
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

    private TaskStatus finish() {
        if (collected.isEmpty()) {
            summary = "no furnace with finished items nearby";
            return TaskStatus.FAILED;
        }
        StringBuilder text = new StringBuilder("collected ");
        String outputs = collected.entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey())
                .reduce((a, b) -> a + ", " + b)
                .orElse("0");
        text.append(outputs).append(" from ").append(collectedStations).append(' ');
        if (stationsUsed.size() == 1) {
            Station station = stationsUsed.iterator().next();
            text.append(collectedStations == 1 ? station.noun() : station.noun() + "s");
        } else {
            text.append(collectedStations == 1 ? "station" : "stations");
        }
        for (String note : notes) {
            text.append("; ").append(note);
        }
        summary = text.toString();
        return TaskStatus.SUCCESS;
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

    private static String reason(BlockBreaker.State state) {
        return switch (state) {
            case PROTECTED -> "it is protected";
            case UNREACHABLE -> "can't reach it";
            default -> "the block is gone";
        };
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
