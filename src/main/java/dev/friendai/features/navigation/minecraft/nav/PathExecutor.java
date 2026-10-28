package dev.friendai.features.navigation.minecraft.nav;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.mining.minecraft.mining.BreakAction;
import dev.friendai.features.navigation.movement.PathSteering;
import dev.friendai.features.navigation.pathing.BlockList;
import dev.friendai.features.navigation.pathing.CellView;
import dev.friendai.features.navigation.pathing.CostModel;
import dev.friendai.features.navigation.pathing.Move;
import dev.friendai.features.navigation.pathing.MoveGenerator;
import dev.friendai.features.navigation.pathing.Path;
import dev.friendai.features.navigation.pathing.PathStep;
import dev.friendai.features.navigation.pathing.PathingConfig;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.task.TaskContext;
import dev.friendai.task.inventory.DropCollector;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Walks one planned route step by step: re-checks the world, digs the cells the plan wanted gone, places the blocks
 * the plan wanted filled, then moves the companion into the next node.
 *
 * <p>Every step is checked against the world as it is now, not as the search saw it: a search runs on a snapshot and
 * several ticks may pass before its route starts. A step that no longer holds fails, and the caller searches again.
 */
public final class PathExecutor {
    public enum State {
        RUNNING,
        DONE,
        FAILED
    }

    /** How many ticks a placement may take before the step is given up. */
    private static final int PLACE_TIMEOUT = 40;
    /** How many ticks the companion may take to rise one block after the jump of a pillar. */
    private static final int JUMP_TIMEOUT = 20;

    private static final double MOVE_SPEED = 1.0;
    /** Blocks per tick while swimming; set directly, because a mob's own water acceleration is very slow. */
    private static final double SWIM_SPEED = 0.14;

    private static final double DIVE_SPEED = 0.15;
    private static final double RISE_SPEED = 0.2;
    private static final int STUCK_WINDOW = 10;
    private static final double STUCK_PROGRESS = 0.15;
    private static final double FEET_TOLERANCE = 0.4;
    private static final double LOOSE_TOLERANCE = 0.7;

    private record FeetTarget(double y, double tolerance) {}
    /** How close a drop must be to be picked up at the feet after a dug cell; a bridge material block is one. */
    private static final double PICKUP_RADIUS = 1.5;
    /** How long the companion picks up after a break: the vanilla pickup delay plus a few ticks to walk onto it. */
    private static final int PICKUP_TICKS = 15;

    private enum Phase {
        CHECK,
        BREAK,
        PLACE,
        MOVE
    }

    private final Path path;
    private final MaterialPicker materials;
    private final PathingConfig config;
    private final BlockList breakList;
    private final PathExecutionGuard guard;
    /** Where blocks this route places are remembered for cleanup, or null when the caller does not want them. */
    private final PlacementLedger ledger;
    /** Horizontal length left from each step's start, so the pace decision is O(1) per tick. */
    private final double[] remaining;
    /** A live world view for the per-tick standability test: it re-reads every cell it is asked about. */
    private CellView liveView;

    private int index = -1;
    private Phase phase = Phase.CHECK;
    private final List<Pos> breaks = new ArrayList<>();
    private final List<Pos> places = new ArrayList<>();
    private BreakAction action;
    private boolean jumped;
    private boolean doorOpened;
    private int stepTicks;
    private int placeTicks;
    private int jumpTicks;
    /** Ticks left in which a drop from a dug cell is picked up. Armed only by a break, so walking collects nothing. */
    private int pickupTicks;

    private double stepStartY;
    private int lowestY;
    private double progressX;
    private double progressY;
    private double progressZ;
    private int progressTicks;
    private int nudges;
    private int brokenBlocks;

    public PathExecutor(Path path, MaterialPicker materials, PathingConfig config) {
        this(path, materials, config, PathExecutionGuard.DEFAULT);
    }

    public PathExecutor(Path path, MaterialPicker materials, PathingConfig config, PathExecutionGuard guard) {
        this(path, materials, config, guard, null);
    }

    /**
     * @param ledger records every block this route places, so the caller can take a pillar or bridge down after the
     *     job; null for routes whose placements are meant to stay, like a build.
     */
    public PathExecutor(
            Path path,
            MaterialPicker materials,
            PathingConfig config,
            PathExecutionGuard guard,
            PlacementLedger ledger) {
        this(path, materials, config, guard, ledger, BlockList.parse(config.breakBlacklist(), ignored -> {}));
    }

    public PathExecutor(
            Path path,
            MaterialPicker materials,
            PathingConfig config,
            PathExecutionGuard guard,
            PlacementLedger ledger,
            BlockList breakList) {
        this.path = path;
        this.materials = materials;
        this.config = config;
        this.guard = guard;
        this.ledger = ledger;
        this.breakList = breakList;
        this.remaining = new double[path.steps().size() + 1];
        for (int i = path.steps().size() - 1; i >= 0; i--) {
            PathStep step = path.steps().get(i);
            double dx = step.to().x() - step.from().x();
            double dz = step.to().z() - step.from().z();
            this.remaining[i] = this.remaining[i + 1] + Math.hypot(dx, dz);
        }
    }

    /** How many steps are finished; the step being worked on starts at this index. */
    public int stepIndex() {
        return Math.max(0, index);
    }

    /** Number of blocks this executor actually destroyed; vanished blocks do not count. */
    public int brokenBlocks() {
        return brokenBlocks;
    }

    /** Horizontal blocks left on the route from the body, for the pace decision. */
    public double remainingDistance(CompanionEntity companion) {
        if (remaining.length <= 1) {
            return 0.0;
        }
        if (index < 0) {
            return remaining[0];
        }
        if (index >= path.steps().size()) {
            return 0.0;
        }
        PathStep step = path.steps().get(index);
        double stepX = step.to().x() - step.from().x();
        double stepZ = step.to().z() - step.from().z();
        double length = Math.hypot(stepX, stepZ);
        if (length < 1.0e-6) {
            return remaining[index];
        }
        double dx = companion.getX() - (step.from().x() + 0.5);
        double dz = companion.getZ() - (step.from().z() + 0.5);
        double done = Math.max(0.0, Math.min(length, (dx * stepX + dz * stepZ) / length));
        return remaining[index + 1] + (length - done);
    }

    public State tick(TaskContext c) {
        CompanionEntity companion = c.companion();
        companion.setPathing(true);
        if (pickupTicks > 0) {
            pickupTicks--;
            DropCollector.pickupAtFeet(c, PICKUP_RADIUS, null);
        }
        if (index < 0) {
            if (path.steps().isEmpty()) {
                return State.DONE;
            }
            begin(c, 0);
        }
        if (index >= path.steps().size()) {
            return State.DONE;
        }
        PathStep step = path.steps().get(index);
        if (++stepTicks > (int) (step.cost() * 3) + 40) {
            return fail(c);
        }
        if (companion.getY() < lowestY - 1.0) {
            return fail(c);
        }
        return switch (phase) {
            case CHECK -> {
                State checked = check(c);
                if (checked == State.RUNNING && phase == Phase.MOVE && step.move() == Move.WALK) {
                    yield move(c, step);
                }
                yield checked;
            }
            case BREAK -> breakCell(c);
            case PLACE -> place(c, step);
            case MOVE -> move(c, step);
        };
    }

    /** Stops navigation and clears the crack overlay; safe to call at any time. */
    public void stop(TaskContext c) {
        if (action != null) {
            action.stop(c);
            action = null;
        }
        c.companion().getNavigation().stop();
        c.companion().setPathing(false);
    }

    private void begin(TaskContext c, int next) {
        index = next;
        PathStep step = path.steps().get(next);
        breaks.clear();
        breaks.addAll(step.breaks());
        places.clear();
        places.addAll(step.places());
        action = null;
        jumped = false;
        doorOpened = false;
        stepTicks = 0;
        placeTicks = 0;
        jumpTicks = 0;
        stepStartY = c.companion().getY();
        lowestY = Math.min(step.from().y(), step.to().y());
        progressX = c.companion().getX();
        progressY = c.companion().getY();
        progressZ = c.companion().getZ();
        progressTicks = 0;
        nudges = 0;
        phase = Phase.CHECK;
    }

    private State check(TaskContext c) {
        LevelCellView view = new LevelCellView(c.companion(), config, CostModel.DEFAULTS, breakList);
        liveView = LevelCellView.liveTerrain(c.companion());
        for (int i = breaks.size() - 1; i >= 0; i--) {
            Pos pos = breaks.get(i);
            BlockState state = stateAt(c, pos);
            if (state.isAir()) {
                breaks.remove(i);
            } else if (!view.at(pos).breakable()) {
                return fail(c);
            }
        }
        for (int i = places.size() - 1; i >= 0; i--) {
            Pos pos = places.get(i);
            BlockPos blockPos = new BlockPos(pos.x(), pos.y(), pos.z());
            if (!stateAt(c, pos).canBeReplaced()) {
                places.remove(i);
            } else if (!view.at(pos).placeable() || !c.companion().hands().canInteract(blockPos)) {
                return fail(c);
            }
        }
        if (!breaks.isEmpty()) {
            phase = Phase.BREAK;
        } else if (!places.isEmpty()) {
            phase = Phase.PLACE;
        } else {
            phase = Phase.MOVE;
        }
        return State.RUNNING;
    }

    private State breakCell(TaskContext c) {
        Pos pos = breaks.get(0);
        PathStep step = path.steps().get(index);
        if (!guard.allowBreak(c, step, pos)) {
            if (action != null) {
                action.stop(c);
            }
            return fail(c);
        }
        if (action == null) {
            action = new BreakAction(new BlockPos(pos.x(), pos.y(), pos.z()));
        }
        return switch (action.tick(c, state -> !state.isAir())) {
            case WORKING -> State.RUNNING;
            case BROKEN -> {
                brokenBlocks++;
                action = null;
                breaks.remove(0);
                pickupTicks = PICKUP_TICKS;
                phase = breaks.isEmpty() ? (places.isEmpty() ? Phase.MOVE : Phase.PLACE) : Phase.BREAK;
                yield State.RUNNING;
            }
            case GONE -> {
                action = null;
                breaks.remove(0);
                phase = breaks.isEmpty() ? (places.isEmpty() ? Phase.MOVE : Phase.PLACE) : Phase.BREAK;
                yield State.RUNNING;
            }
            case BLOCKED -> fail(c);
        };
    }

    private State place(TaskContext c, PathStep step) {
        CompanionEntity companion = c.companion();
        if (placeTicks++ > PLACE_TIMEOUT) {
            return fail(c);
        }
        if (step.move() == Move.PILLAR && !jumped) {
            // The companion must be above the cell it fills: jump first, place once it has risen a block.
            if (jumpTicks++ > JUMP_TIMEOUT) {
                return fail(c);
            }
            if (companion.getY() < stepStartY + 1.0) {
                if (companion.onGround()) {
                    companion.getJumpControl().jump();
                }
                return State.RUNNING;
            }
            jumped = true;
        }
        Pos place = places.get(0);
        Optional<ItemStack> taken = materials.takeCheapest(companion.inventory());
        if (taken.isEmpty()) {
            return fail(c);
        }
        Pos against;
        Direction face;
        if (step.move() == Move.BRIDGE) {
            against = step.from().support();
            face = faceBetween(against, place);
        } else {
            against = new Pos(place.x(), place.y() - 1, place.z());
            face = Direction.UP;
        }
        companion.hands().place(taken.get(), new BlockPos(against.x(), against.y(), against.z()), face);
        if (stateAt(c, place).canBeReplaced()) {
            return fail(c);
        }
        if (ledger != null) {
            ledger.record(new BlockPos(place.x(), place.y(), place.z()), taken.get());
        }
        places.remove(0);
        if (places.isEmpty()) {
            phase = Phase.MOVE;
        }
        return State.RUNNING;
    }

    private State move(TaskContext c, PathStep step) {
        CompanionEntity companion = c.companion();
        if (!guard.allowMove(c, step)) {
            return fail(c);
        }
        if (!MoveGenerator.standable(liveView, step.to())) {
            return fail(c);
        }
        if (step.move() == Move.DOOR && !doorOpened) {
            doorOpened = true;
            BlockPos foot = new BlockPos(step.to().x(), step.to().y(), step.to().z());
            companion.hands().use(foot);
            BlockPos head = foot.above();
            BlockState headState = c.level().getBlockState(head);
            if (headState.hasProperty(BlockStateProperties.OPEN) && !headState.getValue(BlockStateProperties.OPEN)) {
                companion.hands().use(head);
            }
        }
        if (step.move() == Move.DOOR) {
            BlockPos foot = new BlockPos(step.to().x(), step.to().y(), step.to().z());
            for (BlockPos pos : List.of(foot, foot.above())) {
                BlockState state = c.level().getBlockState(pos);
                if (state.hasProperty(BlockStateProperties.OPEN) && !state.getValue(BlockStateProperties.OPEN)) {
                    return State.RUNNING;
                }
            }
        }
        FeetTarget target = feetTarget(c, step);
        boolean climbing = step.move() == Move.CLIMB;
        boolean holding = climbing || (!companion.onGround() && step.move() == Move.DESCEND);
        float yaw =
                PathSteering.yaw(path.steps(), index, companion.getX(), companion.getZ(), companion.getYRot(), holding);
        companion.facing().travelYaw(yaw);
        double radians = Math.toRadians(yaw);
        companion
                .getMoveControl()
                .setWantedPosition(
                        companion.getX() - Math.sin(radians),
                        target.y(),
                        companion.getZ() + Math.cos(radians),
                        MOVE_SPEED);
        boolean up = step.to().y() > step.from().y();
        if (companion.isInWater()) {
            double dx = step.to().x() + 0.5 - companion.getX();
            double dz = step.to().z() + 0.5 - companion.getZ();
            double distance = Math.hypot(dx, dz);
            var velocity = companion.getDeltaMovement();
            // Never step further than the distance left, so it does not overshoot and shuffle back and forth.
            double horizontal = distance < 1.0e-4 ? 0.0 : Math.min(SWIM_SPEED, distance) / distance;
            companion.setDeltaMovement(dx * horizontal, swimVertical(c, step, target, velocity.y, up), dz * horizontal);
        }
        if (up
                && (step.move() == Move.CLIMB
                        || step.move() == Move.SWIM
                        || step.move() == Move.ASCEND
                        || step.move() == Move.DIG_ASCEND)
                && (climbing || companion.onClimbable() || companion.isInWater())) {
            var velocity = companion.getDeltaMovement();
            // At the top of a scaffold vanilla switches from climbing to a normal jump.  The climb impulse alone
            // stops at the top collision face, so hand that transition to the mob's jump control below.
            double vertical = climbing && !companion.onClimbable() ? velocity.y : Math.max(velocity.y, 0.2);
            companion.setDeltaMovement(climbing ? 0.0 : velocity.x, vertical, climbing ? 0.0 : velocity.z);
        }
        if (++progressTicks >= STUCK_WINDOW) {
            double dx = companion.getX() - progressX;
            double dy = companion.getY() - progressY;
            double dz = companion.getZ() - progressZ;
            double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (moved < STUCK_PROGRESS) {
                if (nudges > 0) {
                    return fail(c);
                }
                companion.getJumpControl().jump();
                nudges = 1;
            } else {
                nudges = 0;
            }
            progressX = companion.getX();
            progressY = companion.getY();
            progressZ = companion.getZ();
            progressTicks = 0;
        }
        if (step.move() == Move.ASCEND
                || step.move() == Move.DIG_ASCEND
                || step.move() == Move.JUMP_GAP
                || (step.move() == Move.PILLAR && !jumped)) {
            if (companion.onGround() && needsJump(companion, step, target)) {
                companion.getJumpControl().jump();
            }
        }
        if (climbing && !companion.onClimbable() && companion.getY() < target.y() - target.tolerance()) {
            companion.getJumpControl().jump();
        }
        if (arrived(c, step, target) && (companion.onGround() || companion.isInWater() || companion.onClimbable())) {
            int next = index + 1;
            if (next >= path.steps().size()) {
                index = next;
                companion.getNavigation().stop();
                return State.DONE;
            }
            begin(c, next);
        }
        return State.RUNNING;
    }

    /**
     * The vertical speed while in water. A step that ends with its head under water holds the depth of its cell:
     * nothing else makes a mob dive, and the float goal pushes it back up every tick. A step on the surface is left to
     * the float goal, which keeps the body at the right height by itself.
     */
    private static double swimVertical(TaskContext c, PathStep step, FeetTarget target, double current, boolean up) {
        if (step.move() == Move.SWIM || step.move() == Move.DESCEND) {
            BlockPos head =
                    new BlockPos(step.to().x(), step.to().y() + 1, step.to().z());
            if (c.level().getFluidState(head).is(FluidTags.WATER)) {
                double error = target.y() - c.companion().getY();
                return Math.max(-DIVE_SPEED, Math.min(RISE_SPEED, error * 0.5));
            }
        }
        return up ? Math.max(current, RISE_SPEED) : current;
    }

    /**
     * Whether a grounded companion still has to leave the ground for this step. Without the check it hops again each
     * time it lands short of the arrival radius, e.g. on the edge of the block it climbed onto.
     */
    private static boolean needsJump(CompanionEntity companion, PathStep step, FeetTarget target) {
        if (step.move() == Move.PILLAR) {
            return true;
        }
        if (step.move() == Move.ASCEND || step.move() == Move.DIG_ASCEND) {
            return companion.getY() < target.y() - 0.3;
        }
        double dx = companion.getX() - (step.from().x() + 0.5);
        double dz = companion.getZ() - (step.from().z() + 0.5);
        return dx * dx + dz * dz <= 1.0;
    }

    private FeetTarget feetTarget(TaskContext c, PathStep step) {
        BlockPos footPos = new BlockPos(step.to().x(), step.to().y(), step.to().z());
        BlockState foot = c.level().getBlockState(footPos);
        if (isClimbable(foot) || !foot.getFluidState().isEmpty()) {
            return new FeetTarget(step.to().y(), LOOSE_TOLERANCE);
        }
        BlockPos supportPos = footPos.below();
        BlockState support = c.level().getBlockState(supportPos);
        if (isClimbable(support)) {
            return new FeetTarget(step.to().y(), LOOSE_TOLERANCE);
        }
        var shape = support.getCollisionShape(c.level(), supportPos);
        return shape.isEmpty()
                ? new FeetTarget(step.to().y(), LOOSE_TOLERANCE)
                : new FeetTarget(supportPos.getY() + shape.max(Direction.Axis.Y), FEET_TOLERANCE);
    }

    private static boolean isClimbable(BlockState state) {
        return state.is(BlockTags.CLIMBABLE) || state.getBlock() instanceof ScaffoldingBlock;
    }

    private boolean arrived(TaskContext c, PathStep step, FeetTarget target) {
        double dx = c.companion().getX() - (step.to().x() + 0.5);
        double dz = c.companion().getZ() - (step.to().z() + 0.5);
        double radius = arriveRadius(step);
        boolean level = Math.abs(c.companion().getY() - target.y()) <= target.tolerance();
        if (dx * dx + dz * dz <= radius * radius) {
            return level;
        }
        if (level && (step.move() == Move.DESCEND || step.move() == Move.WALK)) {
            // Momentum carried it past the centre (a fall keeps the horizontal speed): going back to the centre
            // would turn it around, so a companion that is past the centre and still in the cell has arrived.
            int sx = step.to().x() - step.from().x();
            int sz = step.to().z() - step.from().z();
            if (Math.abs(sx) + Math.abs(sz) == 1) {
                double along = dx * sx + dz * sz;
                double across = Math.abs(sx == 0 ? dx : dz);
                return along > 0 && along <= 0.5 && across <= 0.5;
            }
        }
        return false;
    }

    /** Mid-run, only a collinear plain step may keep the wider arrival radius. */
    private double arriveRadius(PathStep step) {
        if (index + 1 >= path.steps().size()) {
            return 0.5;
        }
        PathStep next = path.steps().get(index + 1);
        boolean plain = (step.move() == Move.WALK || step.move() == Move.WALK_DIAGONAL || step.move() == Move.SWIM)
                && next.move() == step.move()
                && next.breaks().isEmpty()
                && next.places().isEmpty()
                && step.to().x() - step.from().x()
                        == next.to().x() - next.from().x()
                && step.to().z() - step.from().z()
                        == next.to().z() - next.from().z();
        return plain ? 0.8 : 0.5;
    }

    private State fail(TaskContext c) {
        stop(c);
        return State.FAILED;
    }

    private static BlockState stateAt(TaskContext c, Pos pos) {
        return c.level().getBlockState(new BlockPos(pos.x(), pos.y(), pos.z()));
    }

    private static Direction faceBetween(Pos from, Pos to) {
        int dx = to.x() - from.x();
        if (dx > 0) {
            return Direction.EAST;
        }
        if (dx < 0) {
            return Direction.WEST;
        }
        return to.z() > from.z() ? Direction.SOUTH : Direction.NORTH;
    }
}
