package dev.friedai.task.builtin;

import dev.friedai.task.Task;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.mining.BlockMatcher;
import dev.friedai.task.mining.BlockSearch;
import dev.friedai.task.mining.BreakProgress;
import dev.friedai.task.mining.ToolSelector;
import dev.friedai.task.nav.MoveHelper;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Finds, walks to, breaks and collects N blocks of a kind. */
public final class MineBlocksTask implements Task {
    private static final double REACH = 4.0;
    private static final int COLLECT_TICKS = 10;

    private enum Phase {
        SEARCH,
        WALK,
        BREAK,
        COLLECT
    }

    private final BlockMatcher matcher;
    private final int count;
    private final int radius;
    private final Set<BlockPos> blacklist = new HashSet<>();
    private final MoveHelper mover = new MoveHelper(1.0, 1.5);

    private Phase phase = Phase.SEARCH;
    private BlockPos target;
    private int mined;
    private int breakTicks;
    private int breakNeeded;
    private int collectTicks;
    private String summary = "";

    public MineBlocksTask(BlockMatcher matcher, int count, int radius) {
        this.matcher = matcher;
        this.count = count;
        this.radius = radius;
    }

    @Override
    public String type() {
        return "mine_blocks";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        return switch (phase) {
            case SEARCH -> search(c);
            case WALK -> walk(c);
            case BREAK -> breakBlock(c);
            case COLLECT -> collect(c);
        };
    }

    private TaskStatus search(TaskContext c) {
        if (mined >= count) {
            summary = "mined " + mined + " " + matcher.label();
            return TaskStatus.SUCCESS;
        }
        Optional<BlockPos> next = BlockSearch.nearest(c.level(), c.companion().blockPosition(), radius, matcher, blacklist);
        if (next.isEmpty()) {
            summary = mined == 0
                    ? "no " + matcher.label() + " nearby"
                    : "mined " + mined + " of " + count + ", no more " + matcher.label() + " nearby";
            return TaskStatus.FAILED;
        }
        target = next.get();
        mover.reset();
        phase = Phase.WALK;
        return TaskStatus.RUNNING;
    }

    private TaskStatus walk(TaskContext c) {
        var companion = c.companion();
        if (!matcher.test(c.level().getBlockState(target))) {
            phase = Phase.SEARCH;
            return TaskStatus.RUNNING;
        }
        Vec3 center = Vec3.atCenterOf(target);
        if (companion.getEyePosition().distanceTo(center) <= REACH) {
            companion.getNavigation().stop();
            BlockState state = c.level().getBlockState(target);
            ToolSelector.equipBest(companion, state);
            var tool = companion.getMainHandItem();
            boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
            breakNeeded = BreakProgress.ticksToBreak(state.getDestroySpeed(c.level(), target), tool.getDestroySpeed(state), correct);
            breakTicks = 0;
            phase = Phase.BREAK;
            return TaskStatus.RUNNING;
        }
        if (mover.tick(companion, center) == MoveHelper.State.STUCK) {
            blacklist.add(target);
            phase = Phase.SEARCH;
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus breakBlock(TaskContext c) {
        var companion = c.companion();
        ServerLevel level = c.level();
        if (!matcher.test(level.getBlockState(target))) {
            level.destroyBlockProgress(companion.getId(), target, -1);
            phase = Phase.SEARCH;
            return TaskStatus.RUNNING;
        }
        Vec3 center = Vec3.atCenterOf(target);
        companion.getLookControl().setLookAt(center.x, center.y, center.z);
        if (breakTicks % 5 == 0) {
            companion.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
        }
        breakTicks++;
        level.destroyBlockProgress(companion.getId(), target, Math.min(9, breakTicks * 10 / breakNeeded));
        if (breakTicks >= breakNeeded) {
            level.destroyBlockProgress(companion.getId(), target, -1);
            level.destroyBlock(target, true, companion);
            mined++;
            collectTicks = 0;
            phase = Phase.COLLECT;
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus collect(TaskContext c) {
        c.companion().collectNearbyItems(4.0);
        if (++collectTicks >= COLLECT_TICKS) {
            phase = Phase.SEARCH;
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().getNavigation().stop();
        if (target != null) {
            c.level().destroyBlockProgress(c.companion().getId(), target, -1);
        }
    }

    @Override
    public String describe() {
        return "mining " + count + " " + matcher.label();
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
