package dev.friendai.task.builtin;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.mining.BlockBreaker;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.mining.BlockSearch;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Finds, walks to, breaks and collects N blocks of a kind. */
public final class MineBlocksTask implements Task {
    private final BlockMatcher matcher;
    private final int count;
    private final int radius;
    private final Set<BlockPos> blacklist = new HashSet<>();

    private BlockBreaker breaker;
    private int mined;
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
        if (breaker == null) {
            return search(c);
        }
        return switch (breaker.tick(c)) {
            case WORKING -> TaskStatus.RUNNING;
            case BROKEN -> {
                breaker = null;
                mined++;
                yield TaskStatus.RUNNING;
            }
            case GONE -> {
                breaker = null;
                yield TaskStatus.RUNNING;
            }
            case UNREACHABLE, PROTECTED, OUT_OF_SIGHT -> {
                blacklist.add(breaker.target());
                breaker = null;
                yield TaskStatus.RUNNING;
            }
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
        breaker = new BlockBreaker(next.get(), matcher::test);
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        if (breaker != null) {
            breaker.stop(c);
        } else {
            c.companion().getNavigation().stop();
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
