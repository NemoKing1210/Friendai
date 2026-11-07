package dev.friendai.features.mining.minecraft.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.settings.ResourceSearchSettings;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.knowledge.search.EscortPolicy;
import dev.friendai.features.mining.minecraft.mining.BlockFilter;
import dev.friendai.task.BlockSearchTaskFactory;
import dev.friendai.task.ProgressWatch;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.world.block.BlockMatcher;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

/**
 * The one search a missing target may trigger. It is driven directly rather than through {@link MineBlocksTask#tick}
 * because the surrounding scan reads a live world; the child task here ignores the context it is given.
 */
class MineBlocksTaskTest {
    private static final TaskContext CONTEXT = new TaskContext(null, null, null, null, null);
    private static final Caller CALLER = new Caller(UUID.randomUUID(), "owner", AccessLevel.TRUSTED);

    @Test
    void a_missing_target_starts_one_search_then_rescans() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.SUCCESS);
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true, CALLER);

        assertEquals(MineBlocksTask.Exploration.Step.RUNNING, missing(exploration, 100));
        assertEquals(1, factory.created, "one search starts after the empty scan");
        assertTrue(factory.last.started, "the search is started");
        assertSame(CALLER, factory.lastCaller, "the search keeps the caller the job was built for");

        assertEquals(
                MineBlocksTask.Exploration.Step.RESCAN,
                missing(exploration, 100),
                "a finished search asks for a rescan");
        assertTrue(factory.last.terminated, "a finished search gets its terminal cleanup");
        assertEquals(1, factory.created, "the re-scan must not start a second search");
        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration, 100), "the one search is spent");
        assertEquals(1, factory.created);
    }

    @Test
    void explore_false_never_starts_a_search() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.SUCCESS);
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, false, CALLER);

        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration, 100));
        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration, 100));
        assertEquals(0, factory.created);
    }

    @Test
    void an_unavailable_factory_never_starts_a_search() {
        MineBlocksTask.Exploration exploration =
                new MineBlocksTask.Exploration(BlockSearchTaskFactory.NONE, true, CALLER);

        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration, 100));
        assertFalse(exploration.searching());
    }

    @Test
    void a_failed_search_is_not_retried() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.FAILED);
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true, CALLER);

        assertEquals(MineBlocksTask.Exploration.Step.RUNNING, missing(exploration, 100));
        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration, 100));
        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration, 100));
        assertEquals(1, factory.created);
    }

    @Test
    void a_search_that_outlives_its_budget_is_stopped_and_reports_its_summary() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.RUNNING);
        factory.summary = "search reached its configured tick budget";
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true, CALLER);

        assertEquals(MineBlocksTask.Exploration.Step.RUNNING, missing(exploration, 3));
        assertEquals(MineBlocksTask.Exploration.Step.RUNNING, missing(exploration, 3));
        assertEquals(MineBlocksTask.Exploration.Step.RUNNING, missing(exploration, 3));
        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration, 3), "the third tick spends the budget");
        assertTrue(factory.last.stopped, "an over-budget search is stopped");
        assertTrue(factory.last.terminated, "an over-budget search gets its terminal cleanup");
        assertEquals(3, factory.last.ticks, "exactly maxTicks ticks");
        assertEquals(1, factory.created);
        assertEquals(
                "search reached its configured tick budget",
                exploration.failureSummary(),
                "a capped search must not have its reason discarded");
    }

    @Test
    void a_failed_search_reports_its_own_actionable_reason() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.FAILED);
        factory.summary = "not ready for mining search: need a suitable durable pickaxe";
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true, CALLER);

        assertEquals(MineBlocksTask.Exploration.Step.RUNNING, missing(exploration, 100));
        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration, 100));
        assertEquals(
                "not ready for mining search: need a suitable durable pickaxe",
                exploration.failureSummary(),
                "the job reports the search's reason instead of a generic refusal");
    }

    @Test
    void a_successful_search_reports_no_failure() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.SUCCESS);
        factory.summary = "found minecraft:stone by direct sight";
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true, CALLER);

        assertEquals(MineBlocksTask.Exploration.Step.RUNNING, missing(exploration, 100));
        assertEquals(MineBlocksTask.Exploration.Step.RESCAN, missing(exploration, 100));
        assertEquals("", exploration.failureSummary(), "a success is not a failure reason");
    }

    @Test
    void a_factory_that_returns_null_is_not_asked_again() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.SUCCESS);
        factory.unavailable = true;
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true, CALLER);

        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration, 100));
        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration, 100));
        assertEquals(1, factory.created);
    }

    @Test
    void a_factory_that_throws_is_contained_and_not_retried() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.SUCCESS);
        factory.throwing = true;
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true, CALLER);

        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration, 100));
        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration, 100));
        assertEquals(1, factory.created);
        assertFalse(exploration.searching());
    }

    @Test
    void stopping_the_job_stops_the_running_search() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.RUNNING);
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true, CALLER);
        assertEquals(MineBlocksTask.Exploration.Step.RUNNING, missing(exploration, 100));

        exploration.stop(CONTEXT);

        assertTrue(factory.last.stopped, "a cancelled job stops its search");
        assertFalse(exploration.searching());
    }

    /**
     * The budget is the configured local search budget, not a literal: a job handed a settings record with a short
     * budget must pass that exact number to the search.
     */
    @Test
    void the_search_budget_comes_from_the_resource_search_settings() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.RUNNING);
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true, CALLER);

        assertEquals(MineBlocksTask.Exploration.Step.RUNNING, missing(exploration, 7));

        assertEquals(7, factory.lastMaxTicks, "the configured local budget must reach the search");
    }

    /**
     * A running search is a wait the job declares, not an outer stall: the search's own budget is what ends it, so the
     * runner must leave it alone for the whole configured window even when that window is longer than its stall limit.
     */
    @Test
    void a_running_search_is_a_bounded_wait_not_a_stall() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.RUNNING);
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true, CALLER);
        ProgressWatch watch = new ProgressWatch(0);

        // The last break established the stamp before the search began.
        assertEquals(
                ProgressWatch.Status.PROGRESS,
                watch.observe(7L, exploration.waitingTicks(), exploration.waitingLimitTicks(), 0, 50));

        for (int tick = 1; tick <= 100; tick++) {
            assertEquals(MineBlocksTask.Exploration.Step.RUNNING, missing(exploration, 100), "tick " + tick);
            assertEquals(
                    ProgressWatch.Status.WAITING,
                    watch.observe(7L, exploration.waitingTicks(), exploration.waitingLimitTicks(), tick, 50),
                    "tick " + tick);
        }

        assertEquals(100, exploration.waitingLimitTicks(), "the declared wait is the search's own budget");

        // The following tick spends the budget and ends the wait: the stall window must restart, not fire.
        assertEquals(
                MineBlocksTask.Exploration.Step.NONE, missing(exploration, 100), "the last tick of the search budget");
        assertEquals(-1, exploration.waitingTicks(), "the search is over");
        assertEquals(
                ProgressWatch.Status.PROGRESS,
                watch.observe(7L, exploration.waitingTicks(), exploration.waitingLimitTicks(), 101, 50),
                "the stall window restarts when the declared wait ends");
    }

    /** A call that names its own origin keeps the job there instead of letting the search wander from it. */
    @Test
    void an_explicit_origin_never_delegates_a_search() {
        assertFalse(
                MineBlocksTask.maySearch(true, false, new BlockPos(1, 64, 1)),
                "an explicit near, place or coordinate must not search away from it");
        assertTrue(MineBlocksTask.maySearch(true, false, null), "an unnamed origin still searches");
        assertFalse(MineBlocksTask.maySearch(false, false, null), "explore:false always wins");
        assertFalse(MineBlocksTask.maySearch(true, true, null), "a placed-only job never searches");
    }

    /** The task hands its own caller to the exploration; nothing on the tick path can mint another one. */
    @Test
    void the_task_hands_its_own_caller_to_the_search() {
        Caller caller = new Caller(UUID.randomUUID(), "trusted", AccessLevel.TRUSTED);
        MineBlocksTask task = new MineBlocksTask(
                new BlockFilter(new StubMatcher(), List.of()),
                1,
                8,
                null,
                false,
                false,
                false,
                null,
                0,
                (companion, searchCaller, blockOrTag, radius, maxTicks) -> null,
                true,
                caller);

        assertSame(caller, task.searchCaller(), "the task's caller, not a minted access level");
    }

    /**
     * The explicit-origin miss names the place and does not point at a tool that cannot honour the origin. The
     * companion-centred advice is unchanged, and the suggested navigation tool matches how the origin was named: a
     * point or a player is reached with {@code go_to}, and only a {@code place:} origin has a name for
     * {@code go_to_place}.
     */
    @Test
    void an_explicit_origin_miss_names_the_origin_and_not_a_search_that_cannot_use_it() {
        String miss =
                MineBlocksTask.explicitOriginMiss("minecraft:oak_log", 16, new BlockPos(10, 64, 10), false, false);

        assertTrue(miss.contains("within 16 blocks of 10 64 10"), miss);
        assertTrue(miss.contains("mine_blocks without near"), miss);
        assertTrue(miss.contains("(go_to)"), miss);
        assertFalse(miss.contains("go_to_place"), "a coordinates origin has no place name for go_to_place");
        assertFalse(miss.contains("search_resource"), "search_resource cannot search around the named origin");
        String placeMiss =
                MineBlocksTask.explicitOriginMiss("minecraft:oak_log", 16, new BlockPos(10, 64, 10), false, true);
        assertTrue(placeMiss.contains("(go_to_place)"), placeMiss);
        assertTrue(placeMiss.contains("mine_blocks without near"), placeMiss);
        assertTrue(
                MineBlocksTask.explicitOriginMiss("minecraft:oak_log", 16, new BlockPos(10, 64, 10), true, false)
                        .startsWith("could not reach any"),
                "a found-but-unreachable block must not be described as unseen");
    }

    /** A failed search after some blocks were mined keeps both the achieved count and the search's own reason. */
    @Test
    void a_failed_search_after_mining_keeps_the_count_and_the_reason() {
        assertEquals(
                "mined 1 of 2, search reached its configured tick budget",
                MineBlocksTask.minedThenReason(1, 2, "search reached its configured tick budget"),
                "a job that mined something must not drop its count when the later search fails");
        assertEquals(
                "no minecraft:spruce_log observed or remembered nearby",
                MineBlocksTask.minedThenReason(0, 2, "no minecraft:spruce_log observed or remembered nearby"),
                "a job that mined nothing says only the reason");
    }

    private static MineBlocksTask.Exploration.Step missing(MineBlocksTask.Exploration exploration, int maxTicks) {
        return exploration.missing(CONTEXT, "minecraft:stone", 16, settings(maxTicks));
    }

    private static ResourceSearchSettings settings(int localTicks) {
        return new ResourceSearchSettings(true, true, 64, localTicks, 512, 12_000, EscortPolicy.Config.DEFAULTS);
    }

    /** A matcher that needs no block registry, which a unit test does not bootstrap. */
    private static final class StubMatcher implements BlockMatcher {
        @Override
        public boolean test(BlockState state) {
            return false;
        }

        @Override
        public String label() {
            return "minecraft:stone";
        }
    }

    private static final class RecordingFactory implements BlockSearchTaskFactory {
        private final TaskStatus result;
        int created;
        boolean unavailable;
        boolean throwing;
        String summary = "";
        Caller lastCaller;
        int lastMaxTicks;
        ScriptedTask last;

        RecordingFactory(TaskStatus result) {
            this.result = result;
        }

        @Override
        public Task create(CompanionEntity companion, Caller caller, String blockOrTag, int radius, int maxTicks) {
            created++;
            lastCaller = caller;
            lastMaxTicks = maxTicks;
            if (throwing) {
                throw new IllegalStateException("search composition failed");
            }
            if (unavailable) {
                return null;
            }
            last = new ScriptedTask(result, summary);
            return last;
        }
    }

    private static final class ScriptedTask implements Task {
        private final TaskStatus result;
        private final String summary;
        boolean started;
        boolean stopped;
        boolean terminated;
        int ticks;

        ScriptedTask(TaskStatus result, String summary) {
            this.result = result;
            this.summary = summary;
        }

        @Override
        public String type() {
            return "search_resource";
        }

        @Override
        public void start(TaskContext c) {
            started = true;
        }

        @Override
        public TaskStatus tick(TaskContext c) {
            ticks++;
            return result;
        }

        @Override
        public void stop(TaskContext c) {
            stopped = true;
        }

        @Override
        public void onTerminal(TaskContext c) {
            terminated = true;
        }

        @Override
        public String describe() {
            return "searching for minecraft:stone";
        }

        @Override
        public String resultSummary() {
            return summary;
        }
    }
}
