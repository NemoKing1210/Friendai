package dev.friendai.task.builtin;

import dev.friendai.debug.DebugAction;
import dev.friendai.debug.WorkCell;
import dev.friendai.debug.WorkKind;
import dev.friendai.debug.WorkSet;
import dev.friendai.debug.WorkStatus;
import dev.friendai.features.mining.tree.FellPlan;
import dev.friendai.features.mining.tree.FellPlanner;
import dev.friendai.features.mining.tree.TreeCell;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.autonomy.PlaceGuard;
import dev.friendai.task.build.Scaffold;
import dev.friendai.task.build.ScaffoldCleanupTask;
import dev.friendai.task.inventory.DropCollector;
import dev.friendai.task.mining.BlockBreaker;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.mining.TreeFinder;
import dev.friendai.task.nav.MoveHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Fells one tree chosen when the task was made. A pure {@link FellPlanner} plan puts the companion beside the trunk
 * once, the climb is a single scaffold column, and the logs are cut crown first, layer by layer: the drops fall to the
 * foot of the tree where the sweep already looks. The task never pillars per log and never stands on a trunk log while
 * cutting it. A tree too tall for the climb cap, a tree with no clear side, an empty bag and a protected log are
 * refused with their own reason; a cancel leaves the column to {@link ScaffoldCleanupTask}.
 */
public final class FellTreeTask implements Task {
    /** How far from the foot of the tree the sweep after it looks for the drops. */
    private static final int SWEEP_RADIUS = 8;
    /** How close a drop must be to be taken without walking, while the job goes on. */
    private static final double FEET_RADIUS = 1.5;
    /** How many scaffold blocks one felling may climb; a vanilla tree stays far below. */
    static final int MAX_CLIMB = 32;
    /** How many times the companion tries to stand at the chosen column before another side is tried. */
    private static final int MAX_WALK_ATTEMPTS = 2;

    private enum Phase {
        WALK,
        CLIMB,
        CUT,
        CLEANUP,
        SWEEP,
        DONE
    }

    private final TreeFinder.TreeInfo tree;
    private final BlockMatcher matcher;
    private final int limit;
    private final boolean collect;
    private final String label;

    private final Set<BlockPos> remaining;
    /** Every log this task has ever counted, so a log found later is counted once. */
    private final Set<BlockPos> known;
    /** Logs that will not be cut, and why. */
    private final Map<BlockPos, String> blacklist = new LinkedHashMap<>();

    private int total;

    private FellPlan plan;
    private FellPlan.Climb climb;
    private List<FellPlan.Stage> stages = List.of();
    private int stageIndex;
    private int logIndex;
    private int candidateIndex;
    private int moveAttempts;
    private int cutCount;
    private Phase phase = Phase.WALK;

    private MoveHelper mover;
    private Scaffold scaffold;
    private BlockBreaker breaker;
    private DropCollector sweep;
    private TaskStatus pendingStatus;
    private BlockPos lastBroken;
    private long startTick;
    private String summary = "";
    private String refusal = "";
    private boolean finished;

    /**
     * @param limit how many logs to cut at most, crown first; 0 means the whole tree
     * @param collect whether to walk around picking the drops up afterwards; drops at the feet are always taken
     */
    public FellTreeTask(TreeFinder.TreeInfo tree, BlockMatcher matcher, int limit, boolean collect) {
        this.tree = tree;
        this.matcher = matcher;
        this.limit = limit;
        this.collect = collect;
        this.remaining = new LinkedHashSet<>(tree.logs());
        this.known = new HashSet<>(tree.logs());
        this.total = tree.logs().size();
        this.label = tree.type().endsWith("_log")
                ? tree.type().substring(0, tree.type().length() - 4)
                : tree.type();
    }

    @Override
    public String type() {
        return "fell_tree";
    }

    @Override
    public void start(TaskContext c) {
        startTick = c.level().getGameTime();
        prepare(c);
    }

    /**
     * Builds the plan and refuses what cannot be done before a block is placed: a tree with no free side, a climb above
     * the cap, an empty bag, or a tree whose every log is protected.
     */
    private void prepare(TaskContext c) {
        List<BlockPos> usable = new ArrayList<>();
        for (BlockPos pos : tree.logs()) {
            String reason = protection(c, pos);
            if (reason.isEmpty()) {
                usable.add(pos);
            } else {
                blacklist.put(pos, reason);
            }
        }
        if (usable.isEmpty()) {
            refuse("every log of the " + label + " tree at " + coords(tree.base()) + " is protected");
            return;
        }
        BlockPos feet = c.companion().blockPosition();
        plan = FellPlanner.plan(
                usable.stream().map(FellTreeTask::cell).toList(),
                new TreeCell(feet.getX(), feet.getY(), feet.getZ()),
                limit);
        if (plan.climbs().isEmpty()) {
            refuse("there is no clear side to climb the " + label + " tree at " + coords(tree.base()));
            return;
        }
        FellPlan.Climb best = plan.climbs().get(0);
        if (best.scaffoldNeeded() > MAX_CLIMB) {
            refuse("the " + label + " tree needs " + best.scaffoldNeeded() + " blocks of climbing, more than the "
                    + MAX_CLIMB + " I can build");
            return;
        }
        scaffold = new Scaffold(c.companion());
        int available = scaffold.available(c);
        if (available < best.scaffoldNeeded()) {
            refuse("I need " + best.scaffoldNeeded() + " blocks of dirt or cobblestone to climb the " + label
                    + " tree, I have " + available);
            return;
        }
        phase = Phase.WALK;
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        DropCollector.pickupAtFeet(c, FEET_RADIUS, null);
        remaining.removeIf(
                pos -> !c.level().hasChunkAt(pos) || !matcher.test(c.level().getBlockState(pos)));
        if (!refusal.isEmpty() && !finished) {
            return finish(c, TaskStatus.FAILED, refusal);
        }
        return switch (phase) {
            case WALK -> walk(c);
            case CLIMB -> climb(c);
            case CUT -> cut(c);
            case CLEANUP -> cleanup(c);
            case SWEEP -> sweep(c);
            case DONE -> pendingStatus != null ? pendingStatus : TaskStatus.FAILED;
        };
    }

    /** Picks the first climb side the world allows and walks to the foot of its column. */
    private TaskStatus walk(TaskContext c) {
        if (climb == null && !chooseClimb(c)) {
            return TaskStatus.RUNNING;
        }
        BlockPos column = pos(climb.column());
        if (mover == null) {
            mover = new MoveHelper(1.0, 0.3, false);
        }
        MoveHelper.State state = mover.tick(c.companion(), Vec3.atBottomCenterOf(column));
        if (state == MoveHelper.State.ARRIVED) {
            BlockPos feet = c.companion().blockPosition();
            mover.stop(c);
            mover = null;
            if (feet.getX() == column.getX() && feet.getZ() == column.getZ()) {
                phase = Phase.CLIMB;
                return TaskStatus.RUNNING;
            }
            if (++moveAttempts >= MAX_WALK_ATTEMPTS) {
                nextClimb();
            }
        } else if (state == MoveHelper.State.STUCK) {
            mover.reset(c);
            if (++moveAttempts >= MAX_WALK_ATTEMPTS) {
                nextClimb();
            }
        }
        return TaskStatus.RUNNING;
    }

    /**
     * Accepts the next climb column whose world column is usable and whose climb the bag can pay for. Returns false
     * once every side has been refused, with {@link #refusal} set.
     */
    private boolean chooseClimb(TaskContext c) {
        String last = "";
        while (candidateIndex < plan.climbs().size()) {
            FellPlan.Climb candidate = plan.climbs().get(candidateIndex);
            String problem = columnProblem(c, candidate);
            if (!problem.isEmpty()) {
                if (last.isEmpty()) {
                    last = problem;
                }
                candidateIndex++;
                continue;
            }
            int need = candidate.scaffoldNeeded();
            if (need > MAX_CLIMB) {
                last = "the " + label + " tree needs " + need + " blocks of climbing, more than the " + MAX_CLIMB
                        + " I can build";
                candidateIndex++;
                continue;
            }
            int available = scaffold.available(c);
            if (available < need) {
                last = "I need " + need + " blocks of dirt or cobblestone to climb the " + label + " tree, I have "
                        + available;
                candidateIndex++;
                continue;
            }
            refusal = "";
            climb = candidate;
            stages = candidate.stages();
            moveAttempts = 0;
            return true;
        }
        refuse("there is no clear side to climb the " + label + " tree at " + coords(tree.base())
                + (last.isEmpty() ? "" : ": " + last));
        return false;
    }

    /** Why the column cannot be climbed, or empty when it can: standable ground and every cell free or clearable. */
    private String columnProblem(TaskContext c, FellPlan.Climb candidate) {
        ServerLevel level = c.level();
        BlockPos base = pos(candidate.column());
        BlockPos floor = base.below();
        if (!level.hasChunkAt(base) || !level.hasChunkAt(floor)) {
            return "the ground at " + coords(base) + " is not loaded";
        }
        BlockState floorState = level.getBlockState(floor);
        if (floorState.getCollisionShape(level, floor).isEmpty()
                || !floorState.getFluidState().isEmpty()) {
            return "there is no solid ground at " + coords(base);
        }
        if (!c.companion().hands().canInteract(base) || PlaceGuard.protects(c, base)) {
            return "the foot of the climb at " + coords(base) + " is protected";
        }
        for (int y = base.getY(); y <= candidate.climbFeetY() + 1; y++) {
            BlockPos pos = new BlockPos(base.getX(), y, base.getZ());
            if (!level.hasChunkAt(pos)) {
                return "the climb at " + coords(pos) + " is not loaded";
            }
            BlockState state = level.getBlockState(pos);
            if (passable(level, pos, state)) {
                continue;
            }
            if (!clearable(state)) {
                return blockId(level, pos) + " blocks the climb at " + coords(pos);
            }
            if (!c.companion().hands().canInteract(pos) || PlaceGuard.protects(c, pos)) {
                return "the leaves at " + coords(pos) + " are protected";
            }
        }
        return "";
    }

    /** Climbs one block at a time, clearing the leaves in the next two cells as it goes. */
    private TaskStatus climb(TaskContext c) {
        BlockPos feet = c.companion().blockPosition();
        int target = climb.climbFeetY();
        if (feet.getY() >= target) {
            phase = Phase.CUT;
            return TaskStatus.RUNNING;
        }
        if (breaker != null) {
            return breakClearing(c);
        }
        for (BlockPos cell : List.of(feet.above(), feet.above(2))) {
            BlockState state = c.level().getBlockState(cell);
            if (passable(c.level(), cell, state)) {
                continue;
            }
            if (clearable(state) && c.companion().hands().canInteract(cell) && !PlaceGuard.protects(c, cell)) {
                breaker = new BlockBreaker(cell, FellTreeTask::clearable, false, false);
                return TaskStatus.RUNNING;
            }
            refusal = "I can't climb through " + blockId(c.level(), cell) + " at " + coords(cell);
            return TaskStatus.RUNNING;
        }
        int left = target - feet.getY();
        if (left > 0 && scaffold.available(c) < left) {
            refusal = "I ran out of dirt or cobblestone while climbing the " + label + " tree";
            return TaskStatus.RUNNING;
        }
        switch (scaffold.pillarTo(c, Math.min(target, feet.getY() + 1), MAX_CLIMB)) {
            case CLIMBING -> {
                return TaskStatus.RUNNING;
            }
            case READY -> {
                if (c.companion().blockPosition().getY() >= target) {
                    phase = Phase.CUT;
                }
                return TaskStatus.RUNNING;
            }
            case FAILED -> {
                refusal = "I couldn't climb the " + label + " tree: " + scaffold.failure();
                return TaskStatus.RUNNING;
            }
        }
        return TaskStatus.RUNNING;
    }

    /** Digs one leaf or vine out of the climb column. */
    private TaskStatus breakClearing(TaskContext c) {
        switch (breaker.tick(c)) {
            case WORKING -> {
                return TaskStatus.RUNNING;
            }
            case BROKEN, GONE -> {
                breaker = null;
                return TaskStatus.RUNNING;
            }
            default -> {
                BlockPos cell = breaker.target() != null
                        ? breaker.target()
                        : breaker.candidates().get(0);
                breaker = null;
                refusal = "I can't clear " + coords(cell) + " on the way up the " + label + " tree";
                return TaskStatus.RUNNING;
            }
        }
    }

    /** Cuts the stages crown first, descending one layer at a time. */
    private TaskStatus cut(TaskContext c) {
        while (stageIndex < stages.size()) {
            FellPlan.Stage stage = stages.get(stageIndex);
            if (logIndex >= stage.logs().size()) {
                stageIndex++;
                logIndex = 0;
                continue;
            }
            if (logIndex == 0 && c.companion().blockPosition().getY() > stage.feetY()) {
                Scaffold.State down = scaffold.descendTo(c, stage.feetY());
                if (down == Scaffold.State.CLIMBING) {
                    return TaskStatus.RUNNING;
                }
                // A step that cannot be taken leaves the companion where it is; the logs it cannot reach are named.
            }
            BlockPos target = pos(stage.logs().get(logIndex));
            if (!c.level().hasChunkAt(target) || !matcher.test(c.level().getBlockState(target))) {
                logIndex++;
                continue;
            }
            if (blacklist.containsKey(target)) {
                logIndex++;
                continue;
            }
            if (breaker == null) {
                breaker = new BlockBreaker(List.of(target), matcher::test, false, false, false, false);
            }
            BlockPos reached = breaker.target() != null ? breaker.target() : target;
            switch (breaker.tick(c)) {
                case WORKING -> {
                    return TaskStatus.RUNNING;
                }
                case BROKEN -> {
                    BlockPos broken = breaker.target();
                    breaker = null;
                    lastBroken = broken;
                    remaining.remove(broken);
                    cutCount++;
                    adopt(c, broken);
                    logIndex++;
                }
                case GONE -> {
                    breaker = null;
                    logIndex++;
                }
                case UNREACHABLE -> {
                    breaker = null;
                    blacklist.put(reached, "out of reach");
                    logIndex++;
                }
                case PROTECTED -> {
                    breaker = null;
                    String reason = protection(c, reached);
                    blacklist.put(reached, reason.isEmpty() ? "protected" : reason);
                    logIndex++;
                }
                case OUT_OF_SIGHT -> {
                    breaker = null;
                    blacklist.put(reached, "out of sight");
                    logIndex++;
                }
            }
        }
        return extras(c);
    }

    /** Cuts what the plan did not cover, such as a log the first scan missed, from where the companion stands. */
    private TaskStatus extras(TaskContext c) {
        List<BlockPos> left = new ArrayList<>();
        for (BlockPos pos : remaining) {
            if (!blacklist.containsKey(pos)) {
                left.add(pos);
            }
        }
        left.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed());
        for (BlockPos target : left) {
            if (limit > 0 && cutCount >= limit) {
                break;
            }
            if (breaker == null) {
                breaker = new BlockBreaker(List.of(target), matcher::test, false, false, false, false);
            }
            BlockPos reached = breaker.target() != null ? breaker.target() : target;
            switch (breaker.tick(c)) {
                case WORKING -> {
                    return TaskStatus.RUNNING;
                }
                case BROKEN -> {
                    BlockPos broken = breaker.target();
                    breaker = null;
                    lastBroken = broken;
                    remaining.remove(broken);
                    cutCount++;
                    adopt(c, broken);
                }
                case GONE -> {
                    breaker = null;
                    remaining.remove(target);
                }
                case UNREACHABLE -> {
                    breaker = null;
                    blacklist.put(reached, "out of reach");
                }
                case PROTECTED -> {
                    breaker = null;
                    String reason = protection(c, reached);
                    blacklist.put(reached, reason.isEmpty() ? "protected" : reason);
                }
                case OUT_OF_SIGHT -> {
                    breaker = null;
                    blacklist.put(reached, "out of sight");
                }
            }
        }
        if (limit > 0 && cutCount >= limit) {
            return finish(
                    c,
                    blacklist.isEmpty() ? TaskStatus.SUCCESS : TaskStatus.FAILED,
                    "felled " + cutCount + " of " + total + " " + label + " logs (limit)");
        }
        for (BlockPos pos : remaining) {
            blacklist.putIfAbsent(pos, "no stance reaches it");
        }
        TaskStatus status = blacklist.isEmpty() && remaining.isEmpty() ? TaskStatus.SUCCESS : TaskStatus.FAILED;
        return finish(c, status, "felled " + (total - remaining.size()) + " of " + total + " " + label + " logs");
    }

    /** Removes the column from the top down before the task finishes. */
    private TaskStatus cleanup(TaskContext c) {
        if (scaffold.takeDown(c) == Scaffold.State.CLIMBING) {
            return TaskStatus.RUNNING;
        }
        switch (scaffold.removeAll()) {
            case CLIMBING -> {
                return TaskStatus.RUNNING;
            }
            case READY -> {
                if (scaffold.used() > 0) {
                    summary += " and removed";
                }
                return startSweep(c);
            }
            case FAILED -> {
                summary += "; left " + scaffold.left() + " scaffold blocks"
                        + (scaffold.failure().isBlank() ? "" : " (" + scaffold.failure() + ")");
                pendingStatus = TaskStatus.FAILED;
                return startSweep(c);
            }
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus sweep(TaskContext c) {
        if (sweep.tick(c) == DropCollector.State.DONE) {
            sweep = null;
            phase = Phase.DONE;
            return pendingStatus;
        }
        return TaskStatus.RUNNING;
    }

    /** Ends the task, first removing the scaffold it placed; the summary carries the counters and the refusals. */
    private TaskStatus finish(TaskContext c, TaskStatus status, String text) {
        finished = true;
        summary = text + counters(c);
        pendingStatus = status;
        if (scaffold != null && !scaffold.placed().isEmpty()) {
            phase = Phase.CLEANUP;
            return TaskStatus.RUNNING;
        }
        if (refusal.isEmpty() && (scaffold == null || scaffold.used() > 0 || cutCount > 0)) {
            return startSweep(c);
        }
        phase = Phase.DONE;
        return pendingStatus;
    }

    private String counters(TaskContext c) {
        StringBuilder text = new StringBuilder();
        text.append(" in ").append(c.level().getGameTime() - startTick).append(" ticks");
        if (scaffold != null && scaffold.used() > 0) {
            text.append("; ").append(scaffold.used()).append(" scaffold blocks placed");
        }
        if (!blacklist.isEmpty()) {
            text.append("; ")
                    .append(blacklist.size())
                    .append(blacklist.size() == 1 ? " log" : " logs")
                    .append(" could not be cut");
            int shown = 0;
            for (Map.Entry<BlockPos, String> entry : blacklist.entrySet()) {
                if (shown++ == 3) {
                    break;
                }
                text.append(shown == 1 ? " (" : "; ")
                        .append(entry.getValue())
                        .append(" at ")
                        .append(coords(entry.getKey()));
            }
            text.append(')');
        }
        return text.toString();
    }

    private TaskStatus startSweep(TaskContext c) {
        if (!collect || cutCount == 0) {
            phase = Phase.DONE;
            return pendingStatus;
        }
        // The drops of a high log land at the foot of the tree, so the sweep is centred on the ground there.
        BlockPos from = lastBroken != null ? lastBroken : tree.base();
        sweep = new DropCollector(
                Vec3.atCenterOf(new BlockPos(from.getX(), tree.base().getY(), from.getZ())), SWEEP_RADIUS);
        phase = Phase.SWEEP;
        return TaskStatus.RUNNING;
    }

    /** Why a log must not be cut, or empty when it may be. */
    private String protection(TaskContext c, BlockPos pos) {
        if (!c.companion().hands().canInteract(pos)) {
            return "protected by the server";
        }
        var place = PlaceGuard.protecting(c, pos);
        if (place != null) {
            return "inside the " + place.kind().name().toLowerCase(java.util.Locale.ROOT) + " place \"" + place.name()
                    + "\" (r=" + place.radius() + ")";
        }
        return "";
    }

    private void nextClimb() {
        if (climb != null) {
            candidateIndex++;
        }
        climb = null;
        mover = null;
        moveAttempts = 0;
    }

    private void refuse(String reason) {
        refusal = reason;
        phase = Phase.DONE;
    }

    /** Takes in logs next to the one just broken that the first scan did not see, such as past an unloaded chunk. */
    private void adopt(TaskContext c, BlockPos broken) {
        for (var side : net.minecraft.core.Direction.values()) {
            BlockPos near = broken.relative(side);
            if (!known.contains(near)
                    && c.level().hasChunkAt(near)
                    && matcher.test(c.level().getBlockState(near))) {
                BlockPos pos = near.immutable();
                known.add(pos);
                remaining.add(pos);
                total++;
            }
        }
    }

    @Override
    public void stop(TaskContext c) {
        if (mover != null) {
            mover.stop(c);
            mover = null;
        }
        if (breaker != null) {
            breaker.stop(c);
            breaker = null;
        }
        if (sweep != null) {
            sweep.stop(c);
            sweep = null;
        }
        if (scaffold != null) {
            scaffold.stop();
        }
    }

    /** A cancelled felling takes its column down like a cancelled build. */
    @Override
    public Task cleanupAfterCancel(TaskContext c) {
        return scaffold == null || scaffold.placed().isEmpty() ? null : new ScaffoldCleanupTask(scaffold.placed());
    }

    @Override
    public String describe() {
        BlockPos base = tree.base();
        return "felling " + label + " tree at " + base.getX() + " " + base.getY() + " " + base.getZ();
    }

    @Override
    public List<WorkSet> debugWorkSets() {
        List<WorkCell> cells = new ArrayList<>(known.size());
        Set<BlockPos> planned = new HashSet<>();
        if (plan != null) {
            for (TreeCell cell : plan.cutOrder()) planned.add(pos(cell));
        }
        BlockPos next = null;
        if (stageIndex < stages.size()) {
            FellPlan.Stage stage = stages.get(stageIndex);
            if (logIndex < stage.logs().size()) next = pos(stage.logs().get(logIndex));
        }
        for (BlockPos block : known) {
            WorkStatus status = blacklist.containsKey(block)
                    ? WorkStatus.SKIPPED
                    : !remaining.contains(block)
                            ? WorkStatus.DONE
                            : block.equals(next)
                                    ? WorkStatus.NEXT
                                    : planned.contains(block) ? WorkStatus.PENDING : WorkStatus.SKIPPED;
            cells.add(new WorkCell(
                    block.getX(),
                    block.getY(),
                    block.getZ(),
                    DebugAction.BREAK,
                    status,
                    blacklist.getOrDefault(
                            block, status == WorkStatus.SKIPPED ? "outside selected cut plan" : "Cut log")));
        }
        int doneCount = (int)
                cells.stream().filter(cell -> cell.status() == WorkStatus.DONE).count();
        int nextCount = (int)
                cells.stream().filter(cell -> cell.status() == WorkStatus.NEXT).count();
        int skippedCount = (int) cells.stream()
                .filter(cell -> cell.status() == WorkStatus.SKIPPED)
                .count();
        int pendingCount = cells.size() - doneCount - nextCount - skippedCount;
        return cells.isEmpty()
                ? List.of()
                : List.of(new WorkSet(
                        label + " tree",
                        WorkKind.BLOCKS,
                        cells.size(),
                        doneCount,
                        nextCount,
                        pendingCount,
                        skippedCount,
                        false,
                        cells));
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private static boolean passable(ServerLevel level, BlockPos pos, BlockState state) {
        return state.getCollisionShape(level, pos).isEmpty()
                && state.getFluidState().isEmpty()
                && state.canBeReplaced();
    }

    private static boolean clearable(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(Blocks.VINE);
    }

    private static String blockId(ServerLevel level, BlockPos pos) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(pos).getBlock())
                .toString();
    }

    private static TreeCell cell(BlockPos pos) {
        return new TreeCell(pos.getX(), pos.getY(), pos.getZ());
    }

    private static BlockPos pos(TreeCell cell) {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
