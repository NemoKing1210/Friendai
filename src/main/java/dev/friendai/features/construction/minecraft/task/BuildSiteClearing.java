package dev.friendai.features.construction.minecraft.task;

import dev.friendai.features.construction.minecraft.build.Site;
import dev.friendai.task.TaskContext;
import dev.friendai.world.block.BlockBreaker;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The natural blocks standing where a blueprint goes, taken down one at a time before the first placement. A cell
 * that is no longer natural by the time its turn comes is skipped rather than broken, so a resumed build never digs
 * into a block a player put there, and a break the world refuses is left for the placement pass to report.
 */
final class BuildSiteClearing {
    enum Outcome {
        WORKING,
        DONE
    }

    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private BlockBreaker breaker;
    private int cleared;

    void plan(Collection<BlockPos> cells) {
        queue.addAll(cells);
    }

    /** Whether any cell still has its turn, planned ones included: the material pass asks before it finishes. */
    boolean pending() {
        return !queue.isEmpty() || breaker != null;
    }

    Outcome tick(TaskContext c) {
        if (breaker == null) {
            BlockPos next = nextToClear(c.level());
            if (next == null) {
                return Outcome.DONE;
            }
            // Excavating the site: a cell inside a hill has no view from any standing spot, and the build needs it
            // cleared anyway, so this breaker does not require line of sight.
            breaker = new BlockBreaker(next, Site::isNatural, false);
        }
        boolean released = switch (breaker.tick(c)) {
            case WORKING -> false;
            case BROKEN -> {
                cleared++;
                yield true;
            }
            case GONE, UNREACHABLE, PROTECTED, OUT_OF_SIGHT -> true;
        };
        if (released) {
            breaker = null;
        }
        return Outcome.WORKING;
    }

    private BlockPos nextToClear(ServerLevel level) {
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            BlockState state = level.getBlockState(pos);
            if (Site.isNatural(state) && !state.isAir()) {
                return pos;
            }
        }
        return null;
    }

    void stop(TaskContext c) {
        if (breaker != null) {
            breaker.stop(c);
        }
    }

    int cleared() {
        return cleared;
    }

    /** The cell being cleared, or the next one if the breaker is between blocks; null once nothing is left. */
    BlockPos target() {
        return breaker == null ? queue.peek() : breaker.target();
    }
}
