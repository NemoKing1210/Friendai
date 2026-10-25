package dev.friendai.task.nav;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.pathing.CostModel;
import dev.friendai.pathing.MoveGenerator;
import dev.friendai.pathing.Move;
import dev.friendai.pathing.Path;
import dev.friendai.pathing.PathStep;
import dev.friendai.pathing.PathingConfig;
import dev.friendai.pathing.Pos;
import dev.friendai.task.TaskContext;
import dev.friendai.task.inventory.DropCollector;
import dev.friendai.task.mining.BreakAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.item.ItemStack;
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
    public enum State { RUNNING, DONE, FAILED }

    /** How many ticks a placement may take before the step is given up. */
    private static final int PLACE_TIMEOUT = 40;
    /** How many ticks the companion may take to rise one block after the jump of a pillar. */
    private static final int JUMP_TIMEOUT = 20;
    private static final double MOVE_SPEED = 1.0;
    private static final int STUCK_WINDOW = 10;
    private static final double STUCK_PROGRESS = 0.15;
    private static final double FEET_TOLERANCE = 0.4;
    private static final double LOOSE_TOLERANCE = 0.7;

    private record FeetTarget(double y, double tolerance) {}
    /** How close a drop must be to be picked up at the feet after a dug cell; a bridge material block is one. */
    private static final double PICKUP_RADIUS = 1.5;
    /** How long the companion picks up after a break: the vanilla pickup delay plus a few ticks to walk onto it. */
    private static final int PICKUP_TICKS = 15;

    private enum Phase { CHECK, BREAK, PLACE, MOVE }

    private final Path path;
    private final MaterialPicker materials;
    private final PathingConfig config;

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

    public PathExecutor(Path path, MaterialPicker materials, PathingConfig config) {
        this.path = path;
        this.materials = materials;
        this.config = config;
    }

    /** How many steps are finished; the step being worked on starts at this index. */
    public int stepIndex() {
        return Math.max(0, index);
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
            case CHECK -> check(c);
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
        LevelCellView view = new LevelCellView(c.companion(), config, CostModel.DEFAULTS);
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
        if (action == null) {
            Pos pos = breaks.get(0);
            action = new BreakAction(new BlockPos(pos.x(), pos.y(), pos.z()));
        }
        return switch (action.tick(c, state -> !state.isAir())) {
            case WORKING -> State.RUNNING;
            case BROKEN, GONE -> {
                action = null;
                breaks.remove(0);
                pickupTicks = PICKUP_TICKS;
                phase = breaks.isEmpty() ? (places.isEmpty() ? Phase.MOVE : Phase.PLACE) : Phase.BREAK;
                yield State.RUNNING;
            }
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
        places.remove(0);
        if (places.isEmpty()) {
            phase = Phase.MOVE;
        }
        return State.RUNNING;
    }

    private State move(TaskContext c, PathStep step) {
        CompanionEntity companion = c.companion();
        LevelCellView current = new LevelCellView(companion, config, CostModel.DEFAULTS);
        if (!MoveGenerator.standable(current, step.to())) {
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
        companion.getMoveControl().setWantedPosition(
                climbing ? companion.getX() : step.to().x() + 0.5,
                target.y(),
                climbing ? companion.getZ() : step.to().z() + 0.5,
                MOVE_SPEED);
        boolean up = step.to().y() > step.from().y();
        if (companion.isInWater()) {
            double dx = step.to().x() + 0.5 - companion.getX();
            double dz = step.to().z() + 0.5 - companion.getZ();
            double distance = Math.hypot(dx, dz);
            var velocity = companion.getDeltaMovement();
            double horizontal = distance < 1.0e-4 ? 0.0 : 0.12 / distance;
            companion.setDeltaMovement(dx * horizontal, up ? Math.max(velocity.y, 0.2) : velocity.y, dz * horizontal);
        }
        if (up && (step.move() == Move.CLIMB || step.move() == Move.SWIM || step.move() == Move.ASCEND)
                && (climbing || companion.onClimbable() || companion.isInWater())) {
            var velocity = companion.getDeltaMovement();
            // At the top of a scaffold vanilla switches from climbing to a normal jump.  The climb impulse alone
            // stops at the top collision face, so hand that transition to the mob's jump control below.
            double vertical = climbing && !companion.onClimbable() ? velocity.y : Math.max(velocity.y, 0.2);
            companion.setDeltaMovement(climbing ? 0.0 : velocity.x, vertical,
                    climbing ? 0.0 : velocity.z);
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
        if (step.move() == Move.ASCEND || step.move() == Move.JUMP_GAP
                || (step.move() == Move.PILLAR && !jumped)) {
            if (companion.onGround()) {
                companion.getJumpControl().jump();
            }
        }
        if (climbing && !companion.onClimbable() && companion.getY() < target.y() - target.tolerance()) {
            companion.getJumpControl().jump();
        }
        if (arrived(c, step) && (companion.onGround() || companion.isInWater() || companion.onClimbable())) {
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

    private boolean arrived(TaskContext c, PathStep step) {
        FeetTarget target = feetTarget(c, step);
        double dx = c.companion().getX() - (step.to().x() + 0.5);
        double dz = c.companion().getZ() - (step.to().z() + 0.5);
        double radius = arriveRadius(step);
        return dx * dx + dz * dz <= radius * radius
                && Math.abs(c.companion().getY() - target.y()) <= target.tolerance();
    }

    /** Mid-run, only a collinear plain step may keep the wider arrival radius. */
    private double arriveRadius(PathStep step) {
        if (index + 1 >= path.steps().size()) {
            return 0.5;
        }
        PathStep next = path.steps().get(index + 1);
        boolean plain = (step.move() == Move.WALK || step.move() == Move.WALK_DIAGONAL)
                && next.move() == step.move() && next.breaks().isEmpty() && next.places().isEmpty()
                && step.to().x() - step.from().x() == next.to().x() - next.from().x()
                && step.to().z() - step.from().z() == next.to().z() - next.from().z();
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
