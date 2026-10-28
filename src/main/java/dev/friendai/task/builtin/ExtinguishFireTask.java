package dev.friendai.task.builtin;

import dev.friendai.fire.BurningCell;
import dev.friendai.fire.ExtinguishPlan;
import dev.friendai.fire.ExtinguishStep;
import dev.friendai.fire.FirePlanner;
import dev.friendai.fire.FireTarget;
import dev.friendai.fire.FireText;
import dev.friendai.pathing.Pos;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.fire.FireHands;
import dev.friendai.task.fire.FireScan;
import dev.friendai.task.fire.RecoverWaterTask;
import dev.friendai.task.mining.BlockBreaker;
import dev.friendai.task.nav.Approach;
import dev.friendai.task.nav.MoveHelper;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Puts one fire out: the companion itself, the owner, a burning mob, one block or every fire near it. The plan
 * comes from the pure {@link FirePlanner}; this task only walks, pours, breaks and reports.
 *
 * <p>A step the world refuses (a protected cell, a failed placement, an approach that gives up) re-plans once with
 * the new facts and then finishes with the named reason, so a task never loops on the same refusal.
 */
public final class ExtinguishFireTask implements Task {
    /** A single fire is a short job; past this something is wrong with the plan. */
    public static final int TIMEOUT_TICKS = 600;

    private static final int REPLANS = 1;

    private final FireTarget target;
    private final int radius;
    private final boolean reflex;
    private final boolean nearby;

    private FireTarget live;
    private ExtinguishPlan plan;
    private int cursor;
    private int ticks;
    private int replans;
    private Approach approach;
    private MoveHelper mover;
    private BlockBreaker breaker;
    private int extinguished;
    private boolean recovered = true;
    /** A source this task poured and has not taken back yet; the cleanup task owns it if the task is cancelled. */
    private BlockPos pendingSource;

    private String summary = "";

    /** Every fire within {@code radius} of where the task started. */
    public static ExtinguishFireTask near(int radius) {
        return new ExtinguishFireTask(new FireTarget.Area(List.of()), radius, false, true);
    }

    public ExtinguishFireTask(FireTarget target, int radius) {
        this(target, radius, false, false);
    }

    /** @param reflex started by {@link dev.friendai.task.fire.FireReflex} rather than by a tool */
    public ExtinguishFireTask(FireTarget target, int radius, boolean reflex) {
        this(target, radius, reflex, false);
    }

    private ExtinguishFireTask(FireTarget target, int radius, boolean reflex, boolean nearby) {
        this.target = target;
        this.live = target;
        this.radius = radius;
        this.reflex = reflex;
        this.nearby = nearby;
    }

    public boolean reflex() {
        return reflex;
    }

    @Override
    public String type() {
        return "extinguish_fire";
    }

    @Override
    public void start(TaskContext c) {
        replan(c);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        ticks++;
        if (ticks > TIMEOUT_TICKS) {
            return fail("it took too long");
        }
        if (plan == null) {
            replan(c);
        }
        if (cursor >= plan.steps().size()) {
            if (resolved(c)) {
                return settle();
            }
            if (replans++ < REPLANS) {
                replan(c);
                return TaskStatus.RUNNING;
            }
            return fail("the fire would not go out");
        }
        while (cursor < plan.steps().size()) {
            ExtinguishStep step = plan.steps().get(cursor);
            if (step instanceof ExtinguishStep.GiveUp giveUp) {
                return fail(giveUp.reason());
            }
            StepResult result = run(c, step);
            if (result == StepResult.RUNNING) {
                return TaskStatus.RUNNING;
            }
            if (result == StepResult.REFUSED) {
                if (replans++ < REPLANS) {
                    replan(c);
                    return TaskStatus.RUNNING;
                }
                return fail(plan.giveUp().orElse("the fire would not go out"));
            }
            cursor++;
            return TaskStatus.RUNNING;
        }
        return settle();
    }

    /**
     * A cancelled douse must not leave its source behind: the runner starts {@link RecoverWaterTask} for a source
     * that was poured but not yet taken back.
     */
    @Override
    public Task cleanupAfterCancel(TaskContext c) {
        if (pendingSource == null) {
            return null;
        }
        BlockPos source = pendingSource;
        pendingSource = null;
        return new RecoverWaterTask(Pos.of(source.getX(), source.getY(), source.getZ()));
    }

    @Override
    public void stop(TaskContext c) {
        if (approach != null) {
            approach.stop(c.companion());
            approach = null;
        }
        if (breaker != null) {
            breaker.stop(c);
            breaker = null;
        }
        if (mover != null) {
            mover.stop(c);
            mover = null;
        }
    }

    @Override
    public String describe() {
        return "putting out " + describeTarget();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private String describeTarget() {
        if (nearby) {
            return "the fires within " + radius + " blocks";
        }
        return switch (target) {
            case FireTarget.Self self -> "my own fire";
            case FireTarget.Owner owner -> "the fire on its owner";
            case FireTarget.BurningEntity entity -> "the fire on a " + entity.kind();
            case FireTarget.Block block ->
                "the fire at " + block.pos().x() + " " + block.pos().y() + " "
                        + block.pos().z();
            case FireTarget.Area area -> area.cells().size() + " fires";
        };
    }

    private void replan(TaskContext c) {
        FireScan.Scan scan = FireScan.collect(c, live, radius);
        List<BurningCell> cells = scan.cells();
        if (nearby) {
            cells = FireScan.burningCellsNear(c, radius);
        }
        if (nearby || live instanceof FireTarget.Area) {
            // A reserved fire (the cooking campfire, a hive smoker) is not ours to put out, so it must not keep the
            // area unresolved either.
            List<BurningCell> ours =
                    cells.stream().filter(cell -> !cell.rails().reserved()).toList();
            live = new FireTarget.Area(ours.stream().map(BurningCell::pos).toList());
            cells = ours;
        }
        plan = FirePlanner.plan(live, scan.facts(), cells);
        cursor = 0;
    }

    private StepResult run(TaskContext c, ExtinguishStep step) {
        return switch (step) {
            case ExtinguishStep.PlaceWater water -> pour(c, water.pos(), false);
            case ExtinguishStep.DouseCampfire campfire -> pour(c, campfire.pos(), true);
            case ExtinguishStep.RecoverBucket bucket -> recover(c, bucket.pos());
            case ExtinguishStep.EnterWater water -> enter(c, water.pos());
            case ExtinguishStep.StandInPowderSnow snow -> powderSnow(c, snow.pos());
            case ExtinguishStep.BreakFire fire -> breakFire(c, fire.pos());
            case ExtinguishStep.GiveUp giveUp -> StepResult.REFUSED;
        };
    }

    private StepResult pour(TaskContext c, Pos pos, boolean campfire) {
        BlockPos cell = blockPos(pos);
        Approach.State state = approach(c, cell);
        if (state == Approach.State.MOVING) {
            return StepResult.RUNNING;
        }
        if (state == Approach.State.UNREACHABLE) {
            return StepResult.REFUSED;
        }
        FireHands hands = new FireHands(c);
        if (campfire) {
            if (!hands.douseCampfire(cell)) {
                return StepResult.REFUSED;
            }
            extinguished++;
            return StepResult.DONE;
        }
        BlockPos source = hands.placeWater(cell);
        if (source == null) {
            return StepResult.REFUSED;
        }
        pendingSource = source;
        extinguished++;
        return StepResult.DONE;
    }

    private StepResult recover(TaskContext c, Pos pos) {
        BlockPos cell = blockPos(pos);
        if (!outAt(c.level().getBlockState(cell))) {
            // The water needs a moment to run into the cell and put the fire out before the source is taken back.
            return ticks % 20 == 0 ? StepResult.REFUSED : StepResult.RUNNING;
        }
        if (approach(c, cell) == Approach.State.MOVING) {
            return StepResult.RUNNING;
        }
        if (new FireHands(c).recoverBucket(cell)) {
            pendingSource = null;
        } else {
            recovered = false;
        }
        return StepResult.DONE;
    }

    private StepResult enter(TaskContext c, Pos pos) {
        if (!c.companion().isOnFire()) {
            extinguished++;
            return StepResult.DONE;
        }
        if (mover == null) {
            mover = new MoveHelper(1.0, 1.0);
        }
        if (mover.tick(c.companion(), Vec3.atBottomCenterOf(blockPos(pos))) == MoveHelper.State.MOVING) {
            return StepResult.RUNNING;
        }
        return ticks % 20 == 0 ? StepResult.REFUSED : StepResult.RUNNING;
    }

    private StepResult powderSnow(TaskContext c, Pos pos) {
        BlockPos cell = blockPos(pos);
        if (approach(c, cell) == Approach.State.MOVING) {
            return StepResult.RUNNING;
        }
        if (!new FireHands(c).placePowderSnow(cell)) {
            return StepResult.REFUSED;
        }
        if (c.companion().isOnFire()) {
            return ticks % 20 == 0 ? StepResult.REFUSED : StepResult.RUNNING;
        }
        extinguished++;
        return StepResult.DONE;
    }

    private StepResult breakFire(TaskContext c, Pos pos) {
        if (breaker == null) {
            breaker = new BlockBreaker(List.of(blockPos(pos)), ExtinguishFireTask::isFire, true, false);
        }
        return switch (breaker.tick(c)) {
            case WORKING -> StepResult.RUNNING;
            case BROKEN, GONE -> {
                breaker = null;
                extinguished++;
                yield StepResult.DONE;
            }
            case PROTECTED, UNREACHABLE, OUT_OF_SIGHT -> {
                breaker = null;
                yield StepResult.REFUSED;
            }
        };
    }

    /** Walks within reach of a cell once; the approach is kept while it keeps the same target. */
    private Approach.State approach(TaskContext c, BlockPos cell) {
        if (approach != null && cell.equals(approach.target())) {
            return approach.tick(c.companion());
        }
        if (approach != null) {
            approach.stop(c.companion());
        }
        approach = new Approach(List.of(cell), false);
        return approach.tick(c.companion());
    }

    private boolean resolved(TaskContext c) {
        ServerLevel level = c.level();
        return switch (live) {
            case FireTarget.Self self -> !c.companion().isOnFire();
            case FireTarget.Owner owner ->
                c.owner().map(ownerPlayer -> !ownerPlayer.isOnFire()).orElse(true);
            case FireTarget.BurningEntity entity ->
                burningEntity(level, blockPos(entity.pos()))
                        .map(target -> !target.isOnFire())
                        .orElse(true);
            case FireTarget.Block block -> !isBurning(level.getBlockState(blockPos(block.pos())));
            case FireTarget.Area area ->
                area.cells().stream().noneMatch(pos -> isBurning(level.getBlockState(blockPos(pos))));
        };
    }

    private static boolean isFire(BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE);
    }

    private static boolean outAt(BlockState state) {
        if (isBurning(state)) {
            return false;
        }
        return !state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE);
    }

    /** A cell still on fire: a fire block, a soul fire, or a campfire that is lit. */
    private static boolean isBurning(BlockState state) {
        if (isFire(state)) {
            return true;
        }
        return state.is(net.minecraft.tags.BlockTags.CAMPFIRES) && state.getValue(CampfireBlock.LIT);
    }

    private TaskStatus settle() {
        if (extinguished == 0) {
            summary = nearby ? "nothing was burning within " + radius + " blocks" : "it was already out";
            return TaskStatus.SUCCESS;
        }
        summary = FireText.done(extinguished);
        if (!recovered) {
            summary += " (the bucket stayed behind)";
        }
        return TaskStatus.SUCCESS;
    }

    private TaskStatus fail(String reason) {
        summary = FireText.gaveUp(reason);
        return TaskStatus.FAILED;
    }

    private static Optional<LivingEntity> burningEntity(ServerLevel level, BlockPos pos) {
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).inflate(1.5)).stream()
                .findFirst();
    }

    private static BlockPos blockPos(Pos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    /** Whether a step finished, is still running, or the world refused it. */
    private enum StepResult {
        DONE,
        RUNNING,
        REFUSED
    }
}
