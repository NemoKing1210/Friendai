package dev.friendai.task.builtin;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.mining.BlockSearch;
import dev.friendai.task.nav.Approach;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * A task that works a vanilla menu block (enchanting table, anvil, grindstone): it finds the block, walks up to it,
 * checks protection, runs {@link #operate} in one tick and always closes the menu so nothing stays inside it.
 */
abstract class MenuBlockTask implements Task {
    /** How far the nearest matching block is looked for when no coordinates are given. */
    private static final int SEARCH_RADIUS = 24;

    private final Block block;
    private final String noun;
    private final @Nullable BlockPos given;

    private BlockPos pos;
    private Approach approach;
    protected String summary = "";

    MenuBlockTask(Block block, String noun, @Nullable BlockPos given) {
        this.block = block;
        this.noun = noun;
        this.given = given;
    }

    /** Works the menu at pos: returns SUCCESS or FAILED and sets {@link #summary}. */
    abstract TaskStatus operate(TaskContext c, BlockPos pos);

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        if (pos == null) {
            pos = locate(c);
            if (pos == null) {
                return TaskStatus.FAILED;
            }
            approach = new Approach(pos);
        }
        if (approach != null) {
            switch (approach.tick(c.companion())) {
                case UNREACHABLE -> {
                    summary = "can't reach the " + noun + " at " + coords(pos);
                    return TaskStatus.FAILED;
                }
                case MOVING -> {
                    return TaskStatus.RUNNING;
                }
                case IN_REACH -> approach = null;
            }
        }
        if (!c.companion().hands().canInteract(pos)) {
            summary = "the " + noun + " at " + coords(pos) + " is protected";
            return TaskStatus.FAILED;
        }
        try {
            return operate(c, pos);
        } finally {
            c.companion().hands().closeMenu();
        }
    }

    @Override
    public void stop(TaskContext c) {
        if (approach != null) {
            approach.stop(c.companion());
            approach = null;
        }
        c.companion().hands().closeMenu();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private @Nullable BlockPos locate(TaskContext c) {
        if (given != null) {
            if (!c.level().getBlockState(given).is(block)) {
                summary = "there is no " + noun + " at " + coords(given);
                return null;
            }
            return given;
        }
        BlockMatcher matcher = BlockMatcher.parse(
                        BuiltInRegistries.BLOCK.getKey(block).toString())
                .orElseThrow();
        BlockPos found = BlockSearch.nearest(c.level(), c.companion().blockPosition(), SEARCH_RADIUS, matcher, Set.of())
                .orElse(null);
        if (found == null) {
            summary = "no " + noun + " within " + SEARCH_RADIUS + " blocks";
        }
        return found;
    }

    static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
