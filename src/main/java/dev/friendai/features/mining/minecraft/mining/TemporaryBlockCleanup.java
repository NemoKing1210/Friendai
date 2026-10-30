package dev.friendai.features.mining.minecraft.mining;

import dev.friendai.features.navigation.minecraft.nav.PlacementLedger;
import dev.friendai.task.TaskContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Takes down the blocks a route placed to pillar or bridge to a target, highest first, so a finished job leaves no
 * temporary column behind. Only a position that still holds exactly the block the route placed is touched; a block a
 * player substituted is left alone. Removing the block under its own feet is allowed, so a pillar can be taken down
 * from the top.
 */
public final class TemporaryBlockCleanup {
    public enum State {
        WORKING,
        DONE,
        FAILED
    }

    private final PlacementLedger ledger;
    private final List<BlockPos> remaining;
    private BlockBreaker breaker;
    private int removed;
    private int left;
    /** A block the companion is standing on over a drop that would hurt it: kept, and said so. */
    private int kept;

    private String failure = "";

    public TemporaryBlockCleanup(PlacementLedger ledger) {
        this(ledger, ledger.positions());
    }

    /** Takes down only {@code positions}, so a task can remove its own platform while a route keeps its blocks. */
    public TemporaryBlockCleanup(PlacementLedger ledger, List<BlockPos> positions) {
        this.ledger = ledger;
        this.remaining = new ArrayList<>(positions);
        this.remaining.sort(
                Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed());
    }

    public State tick(TaskContext context) {
        ServerLevel level = context.level();
        while (!remaining.isEmpty()) {
            BlockPos pos = remaining.get(0);
            if (!ledger.stillPlaced(level, pos)) {
                remaining.remove(0);
                continue;
            }
            if (breaker == null) {
                breaker = new BlockBreaker(
                        List.of(pos), state -> ledger.stillPlaced(level, pos), false, false, false, true);
            }
            BlockBreaker.State state = breaker.tick(context);
            if (state == BlockBreaker.State.WORKING) {
                return State.WORKING;
            }
            MiningRefusal refusal = breaker.refusal();
            breaker.stop(context);
            breaker = null;
            remaining.remove(0);
            if (state == BlockBreaker.State.BROKEN || state == BlockBreaker.State.GONE) {
                removed++;
            } else if (refusal instanceof MiningRefusal.OwnSupport) {
                // Breaking it would drop the companion into whatever is below; a load-bearing platform stays.
                kept++;
            } else {
                left++;
                if (failure.isEmpty()) {
                    failure = state.name().toLowerCase(java.util.Locale.ROOT) + " at " + pos.toShortString();
                }
            }
        }
        return left == 0 ? State.DONE : State.FAILED;
    }

    public void stop(TaskContext context) {
        if (breaker != null) {
            breaker.stop(context);
            breaker = null;
        }
    }

    public int removed() {
        return removed;
    }

    public int left() {
        return left;
    }

    /** Blocks kept because the companion stood on them and the drop below would have hurt it. */
    public int kept() {
        return kept;
    }

    /** The first refusal that kept a block in the world, or empty. */
    public String failure() {
        return failure;
    }
}
