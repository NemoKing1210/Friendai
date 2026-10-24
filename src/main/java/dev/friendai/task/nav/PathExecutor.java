package dev.friendai.task.nav;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.pathing.CostModel;
import dev.friendai.pathing.Move;
import dev.friendai.pathing.Path;
import dev.friendai.pathing.PathStep;
import dev.friendai.pathing.Pos;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.mining.BreakAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

    private enum Phase { CHECK, BREAK, PLACE, MOVE }

    private final Path path;
    private final MaterialPicker materials;

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
    private double stepStartY;
    private int lowestY;

    public PathExecutor(Path path, MaterialPicker materials) {
        this.path = path;
        this.materials = materials;
    }

    /** How many steps are finished; the step being worked on starts at this index. */
    public int stepIndex() {
        return Math.max(0, index);
    }

    public State tick(TaskContext c) {
        CompanionEntity companion = c.companion();
        companion.setPathing(true);
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
        phase = Phase.CHECK;
    }

    private State check(TaskContext c) {
        LevelCellView view = new LevelCellView(c.companion(), TaskSettings.pathing, CostModel.DEFAULTS);
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
        companion.getMoveControl().setWantedPosition(
                step.to().x() + 0.5, step.to().y(), step.to().z() + 0.5, MOVE_SPEED);
        if (step.move() == Move.ASCEND || step.move() == Move.JUMP_GAP
                || (step.move() == Move.PILLAR && !jumped)) {
            if (companion.onGround()) {
                companion.getJumpControl().jump();
            }
        }
        BlockPos here = companion.blockPosition();
        if ((here.getX() == step.to().x() && here.getY() == step.to().y() && here.getZ() == step.to().z()
                        || nearCentre(companion, step))
                && (companion.onGround() || companion.isInWater())) {
            companion.getNavigation().stop();
            int next = index + 1;
            if (next >= path.steps().size()) {
                index = next;
                return State.DONE;
            }
            begin(c, next);
        }
        return State.RUNNING;
    }

    /**
     * Whether the companion is inside the step's cell. The tolerance stays well under half a block so a companion
     * standing on the previous cell's centre (a whole block away) does not count as arrived; a jump that crosses the
     * cell in one tick still gets sampled inside it.
     */
    private static boolean nearCentre(CompanionEntity companion, PathStep step) {
        double dx = companion.getX() - (step.to().x() + 0.5);
        double dz = companion.getZ() - (step.to().z() + 0.5);
        return dx * dx + dz * dz <= 0.25 && Math.abs(companion.getY() - step.to().y()) <= 0.5;
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
