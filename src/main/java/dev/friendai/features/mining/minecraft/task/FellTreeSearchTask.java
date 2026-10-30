package dev.friendai.features.mining.minecraft.task;

import dev.friendai.features.mining.minecraft.mining.BlockMatcher;
import dev.friendai.features.mining.minecraft.mining.TreeFinder;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Waits for a recent or remembered tree, then hands the work to {@link FellTreeTask}. A tree that is merely
 * remembered must still be visible from where the companion stands; {@link TreeFinder#findObserved} validates it.
 */
public final class FellTreeSearchTask implements Task {
    private static final int TREE_SEARCH_WAIT_TICKS = 100;
    private static final int MAX_OBSERVATIONS = 1_024;

    private final BlockMatcher type;
    private final BlockPos origin;
    private final int radius;
    private final int limit;
    private final boolean collect;
    private final boolean treesOnly;
    private final BlockMatcher logs;
    private FellTreeTask delegate;
    private int waited;
    private String summary = "";

    public FellTreeSearchTask(BlockMatcher type, BlockPos origin, int radius, int limit, boolean collect) {
        this.type = type;
        this.origin = origin.immutable();
        this.radius = Math.max(1, radius);
        this.limit = limit;
        this.collect = collect;
        this.logs = type != null ? type : BlockMatcher.parse("#minecraft:logs").orElseThrow();
        this.treesOnly = type == null || type.label().startsWith("#");
    }

    @Override
    public String type() {
        return "fell_tree";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        if (delegate != null) {
            TaskStatus status = delegate.tick(c);
            summary = delegate.resultSummary();
            return status;
        }
        if (waited++ > TREE_SEARCH_WAIT_TICKS) {
            summary = "no " + logs.label() + " tree within " + radius + " blocks";
            return TaskStatus.FAILED;
        }
        var resources = c.resources();
        int evidenceRadius = Math.min(128, c.companion().blockPosition().distManhattan(origin) + radius);
        Map<BlockPos, String> observed = new LinkedHashMap<>();
        resources
                .recentOrRememberedBlockIds(c.companion(), evidenceRadius, MAX_OBSERVATIONS)
                .forEach((pos, id) -> {
                    if (pos.distSqr(origin) > (double) radius * radius) return;
                    Identifier parsed = Identifier.tryParse(id);
                    if (parsed == null || !BuiltInRegistries.BLOCK.containsKey(parsed)) return;
                    BlockState block = BuiltInRegistries.BLOCK.getValue(parsed).defaultBlockState();
                    if (logs.test(block)) observed.put(pos, id);
                });
        BlockMatcher leaves = BlockMatcher.parse("#minecraft:leaves").orElseThrow();
        Set<BlockPos> observedLeaves =
                resources.recentOrRemembered(c.companion(), leaves, evidenceRadius, MAX_OBSERVATIONS).stream()
                        .filter(pos -> pos.distSqr(origin) <= (double) radius * radius)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        int searchLimit = limit > 0 ? Math.min(256, limit + 8) : 16;
        List<TreeFinder.TreeInfo> found =
                TreeFinder.findObserved((ServerLevel) c.level(), origin, observed, observedLeaves, searchLimit, true)
                        .stream()
                        .filter(tree -> !treesOnly || tree.kind() != TreeFinder.Kind.LOG_STRUCTURE)
                        .toList();
        if (found.isEmpty()) {
            c.observations()
                    .observer(c.companion())
                    .scheduleSweep(c.level().getServer().overworld().getGameTime());
            summary = "looking for a " + logs.label() + " tree within " + radius + " blocks";
            return TaskStatus.RUNNING;
        }
        delegate = new FellTreeTask(found.get(0), logs, limit, collect);
        delegate.start(c);
        summary = delegate.describe();
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        if (delegate != null) {
            delegate.stop(c);
        } else {
            c.companion().getNavigation().stop();
        }
    }

    @Override
    public String describe() {
        return delegate != null
                ? delegate.describe()
                : "looking for a " + logs.label() + " tree within " + radius + " blocks";
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    @Override
    public String saveData() {
        return delegate == null ? null : delegate.saveData();
    }
}
