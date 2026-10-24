package dev.friendai.task.builtin;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.mining.BlockBreaker;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.mining.BlockSearch;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Finds, walks to, breaks and collects N blocks of a kind. */
public final class MineBlocksTask implements Task {
    private final BlockMatcher matcher;
    private final int count;
    private final int radius;
    private final Set<BlockPos> blacklist = new HashSet<>();

    /** How many of the nearest matching blocks one walk may choose from. */
    private static final int CANDIDATES = 8;

    private BlockBreaker breaker;
    private int mined;
    /** The last block mined: the rest of its tree is worked on before any other block is considered. */
    private BlockPos lastBroken;
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
                lastBroken = breaker.target();
                breaker = null;
                mined++;
                yield TaskStatus.RUNNING;
            }
            case GONE -> {
                breaker = null;
                yield TaskStatus.RUNNING;
            }
            case UNREACHABLE -> {
                // None of the eight can be reached: blacklist them all, or the next search finds them again. The
                // trunk this block belonged to is given up with them, so the search may look elsewhere.
                blacklist.addAll(breaker.candidates());
                lastBroken = null;
                breaker = null;
                yield TaskStatus.RUNNING;
            }
            case PROTECTED, OUT_OF_SIGHT -> {
                // Only the block the companion actually stood by: a sibling may still be reachable.
                if (breaker.target() != null) {
                    blacklist.add(breaker.target());
                }
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
        List<BlockPos> candidates = treeCandidates(c);
        if (candidates.isEmpty()) {
            lastBroken = null;
            candidates = new ArrayList<>(BlockSearch.nearestN(
                    c.level(), c.companion().blockPosition(), radius, matcher, blacklist, CANDIDATES));
            candidates.removeIf(blacklist::contains);
        }
        if (candidates.isEmpty()) {
            // Say which it is: nothing around, or blocks that were found and could not be reached.
            boolean hidden = !blacklist.isEmpty();
            if (mined == 0) {
                summary = hidden
                        ? "could not reach any " + matcher.label() + " nearby"
                        : "no " + matcher.label() + " nearby";
            } else {
                summary = "mined " + mined + " of " + count + ", no more " + matcher.label()
                        + (hidden ? " could be reached" : " nearby");
            }
            return TaskStatus.FAILED;
        }
        // The route may pillar up to a block that cannot be reached from the ground, using the material the
        // companion carries, and it digs away whatever hides the block: mining a kind of block means getting it.
        breaker = new BlockBreaker(candidates, matcher::test, true, true, true);
        return TaskStatus.RUNNING;
    }

    /**
     * The blocks of the same tree or vein as the last mined one, so one tree is felled before the next is started.
     * Empty when there is no last block or its surroundings hold nothing of the kind.
     */
    private List<BlockPos> treeCandidates(TaskContext c) {
        if (lastBroken == null) {
            return new ArrayList<>();
        }
        List<BlockPos> seeds = new ArrayList<>(6);
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            seeds.add(lastBroken.relative(side));
        }
        List<BlockPos> result = new ArrayList<>(BlockSearch.connected(c.level(), seeds, matcher, CANDIDATES));
        result.removeIf(blacklist::contains);
        return result;
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
