package dev.friendai.features.mining.minecraft.task;

import dev.friendai.features.mining.minecraft.mining.TreeFinder;
import dev.friendai.features.mining.tree.TreeCandidateRanker;
import dev.friendai.features.mining.tree.TreeCell;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.world.block.BlockMatcher;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    /**
     * How long the companion looks around before saying there is no tree. A trunk ten blocks off is a narrow target
     * for one sweep's rays, so a companion summoned a moment ago needs several sweeps to see it; five seconds made
     * "chop some wood" fail beside a tree in plain view.
     */
    static final int TREE_SEARCH_WAIT_TICKS = 300;

    private static final int MAX_OBSERVATIONS = 1_024;
    /** Rebuilding the observation maps every tick spends the same 1024 entries for no new evidence. */
    private static final int SCAN_INTERVAL_TICKS = 5;

    private final BlockMatcher type;
    private final BlockPos origin;
    private final int radius;
    private final int limit;
    private final boolean collect;
    private final boolean treesOnly;
    private final BlockMatcher logs;
    private final int remaining;
    private final Set<BlockPos> excludedBases;
    private FellTreeTask delegate;
    private int waited;
    private long lastScan;
    private String summary = "";
    private BlockPos selectedBase;

    public FellTreeSearchTask(BlockMatcher type, BlockPos origin, int radius, int limit, boolean collect) {
        this(type, origin, radius, limit, collect, type == null || type.label().startsWith("#"));
    }

    public FellTreeSearchTask(
            BlockMatcher type, BlockPos origin, int radius, int limit, boolean collect, boolean treesOnly) {
        this(type, origin, radius, limit, collect, treesOnly, 0, Set.of());
    }

    /** A positive remaining count enables cost and yield ranking for an obtain-logs request. */
    public FellTreeSearchTask(
            BlockMatcher type,
            BlockPos origin,
            int radius,
            int limit,
            boolean collect,
            boolean treesOnly,
            int remaining,
            Set<BlockPos> excludedBases) {
        this.type = type;
        this.origin = origin.immutable();
        this.radius = Math.max(1, radius);
        this.limit = limit;
        this.collect = collect;
        this.logs = type != null ? type : BlockMatcher.parse("#minecraft:logs").orElseThrow();
        this.treesOnly = treesOnly;
        this.remaining = Math.max(0, remaining);
        this.excludedBases = excludedBases.stream()
                .map(BlockPos::immutable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
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
            summary = "no " + logs.label() + " tree observed or remembered within " + radius
                    + " blocks; use search_resource to search for it, or look_around for a visible exposure";
            return TaskStatus.FAILED;
        }
        long now = c.level().getGameTime();
        if (lastScan != 0 && now - lastScan < SCAN_INTERVAL_TICKS) {
            return TaskStatus.RUNNING;
        }
        lastScan = now;
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
        int searchLimit = remaining > 0 ? 64 : limit > 0 ? Math.min(256, limit + 8) : 16;
        List<TreeFinder.TreeInfo> found = TreeFinder.findObserved(
                        (ServerLevel) c.level(), origin, observed, observedLeaves, searchLimit, remaining <= 0)
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

        if (remaining > 0) {
            return chooseRankedTree(c, found, observed, observedLeaves);
        }
        TreeFinder.TreeInfo chosen = found.get(0);
        selectedBase = chosen.base().immutable();
        delegate = new FellTreeTask(chosen, logs, limit, collect);
        delegate.start(c);
        summary = delegate.describe();
        return TaskStatus.RUNNING;
    }

    /** Ranks at most eight cheap observed groups, then expands and revalidates only the chosen group. */
    private TaskStatus chooseRankedTree(
            TaskContext c,
            List<TreeFinder.TreeInfo> found,
            Map<BlockPos, String> observed,
            Set<BlockPos> observedLeaves) {
        Map<TreeCell, TreeFinder.TreeInfo> byBase = new LinkedHashMap<>();
        for (TreeFinder.TreeInfo tree : found) {
            if (excluded(tree.base())) continue;
            byBase.putIfAbsent(cell(tree.base()), tree);
        }
        List<TreeFinder.TreeInfo> candidates = byBase.values().stream().limit(8).toList();
        Map<TreeCell, TreeCandidateRanker.Candidate> facts = new LinkedHashMap<>();
        for (TreeFinder.TreeInfo tree : candidates) {
            TreeCandidateRanker.Candidate estimate = FellTreeTask.rankFacts(c, tree, logs, collect);
            facts.put(cell(tree.base()), estimate);
        }
        List<TreeCandidateRanker.Candidate> ranked = TreeCandidateRanker.rank(List.copyOf(facts.values()), remaining);
        for (TreeCandidateRanker.Candidate candidate : ranked) {
            TreeFinder.TreeInfo visible = byBase.get(candidate.base());
            if (visible == null) continue;
            Set<BlockPos> seed = new LinkedHashSet<>(visible.logs());
            Map<BlockPos, String> seedIds = new LinkedHashMap<>();
            for (BlockPos pos : seed) {
                String id = observed.get(pos);
                if (id != null) seedIds.put(pos, id);
            }
            List<TreeFinder.TreeInfo> validated =
                    TreeFinder.findObserved((ServerLevel) c.level(), origin, seedIds, observedLeaves, 2, true);
            TreeFinder.TreeInfo chosen = validated.stream()
                    .filter(tree -> !treesOnly || tree.kind() != TreeFinder.Kind.LOG_STRUCTURE)
                    .filter(tree -> tree.logs().stream()
                            .anyMatch(pos -> logs.test(c.level().getBlockState(pos))))
                    .findFirst()
                    .orElse(null);
            if (chosen == null || excluded(chosen.base())) continue;
            TreeCandidateRanker.Candidate checked = FellTreeTask.rankFacts(c, chosen, logs, collect);
            if (TreeCandidateRanker.rank(List.of(checked), remaining).isEmpty()) continue;
            selectedBase = chosen.base().immutable();
            delegate = new FellTreeTask(chosen, logs, limit, collect);
            delegate.start(c);
            summary = delegate.describe();
            return TaskStatus.RUNNING;
        }
        c.observations()
                .observer(c.companion())
                .scheduleSweep(c.level().getServer().overworld().getGameTime());
        summary = "looking for a safe " + logs.label() + " tree within " + radius + " blocks";
        return TaskStatus.RUNNING;
    }

    private boolean excluded(BlockPos base) {
        return excludedBases.stream().anyMatch(pos -> pos.getX() == base.getX() && pos.getZ() == base.getZ());
    }

    private static TreeCell cell(BlockPos pos) {
        return new TreeCell(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Base selected for this attempt, including after the child task finishes. */
    public BlockPos selectedBase() {
        return selectedBase == null ? null : selectedBase.immutable();
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
    public Task cleanupAfterCancel(TaskContext c) {
        return delegate == null ? null : delegate.cleanupAfterCancel(c);
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
