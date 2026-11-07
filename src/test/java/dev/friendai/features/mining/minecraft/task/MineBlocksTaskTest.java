package dev.friendai.features.mining.minecraft.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.task.BlockSearchTaskFactory;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.UUID;
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
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true);

        assertEquals(MineBlocksTask.Exploration.Step.RUNNING, missing(exploration));
        assertEquals(1, factory.created, "one search starts after the empty scan");
        assertTrue(factory.last.started, "the search is started");
        assertSame(CALLER, factory.lastCaller, "the search keeps the direct caller's authority");

        assertEquals(
                MineBlocksTask.Exploration.Step.RESCAN, missing(exploration), "a finished search asks for a rescan");
        assertTrue(factory.last.terminated, "a finished search gets its terminal cleanup");
        assertEquals(1, factory.created, "the re-scan must not start a second search");
        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration), "the one search is spent");
        assertEquals(1, factory.created);
    }

    @Test
    void explore_false_never_starts_a_search() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.SUCCESS);
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, false);

        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration));
        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration));
        assertEquals(0, factory.created);
    }

    @Test
    void an_unavailable_factory_never_starts_a_search() {
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(BlockSearchTaskFactory.NONE, true);

        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration));
        assertFalse(exploration.searching());
    }

    @Test
    void a_failed_search_is_not_retried() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.FAILED);
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true);

        assertEquals(MineBlocksTask.Exploration.Step.RUNNING, missing(exploration));
        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration));
        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration));
        assertEquals(1, factory.created);
    }

    @Test
    void a_search_that_outlives_its_budget_is_stopped_and_reports_its_summary() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.RUNNING);
        factory.summary = "search reached its configured tick budget";
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true);

        assertEquals(MineBlocksTask.Exploration.Step.RUNNING, missing(exploration));
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
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true);

        assertEquals(MineBlocksTask.Exploration.Step.RUNNING, missing(exploration));
        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration));
        assertEquals(
                "not ready for mining search: need a suitable durable pickaxe",
                exploration.failureSummary(),
                "the job reports the search's reason instead of a generic refusal");
    }

    @Test
    void a_successful_search_reports_no_failure() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.SUCCESS);
        factory.summary = "found minecraft:stone by direct sight";
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true);

        assertEquals(MineBlocksTask.Exploration.Step.RUNNING, missing(exploration));
        assertEquals(MineBlocksTask.Exploration.Step.RESCAN, missing(exploration));
        assertEquals("", exploration.failureSummary(), "a success is not a failure reason");
    }

    @Test
    void a_factory_that_returns_null_is_not_asked_again() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.SUCCESS);
        factory.unavailable = true;
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true);

        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration));
        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration));
        assertEquals(1, factory.created);
    }

    @Test
    void a_factory_that_throws_is_contained_and_not_retried() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.SUCCESS);
        factory.throwing = true;
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true);

        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration));
        assertEquals(MineBlocksTask.Exploration.Step.NONE, missing(exploration));
        assertEquals(1, factory.created);
        assertFalse(exploration.searching());
    }

    @Test
    void stopping_the_job_stops_the_running_search() {
        RecordingFactory factory = new RecordingFactory(TaskStatus.RUNNING);
        MineBlocksTask.Exploration exploration = new MineBlocksTask.Exploration(factory, true);
        assertEquals(MineBlocksTask.Exploration.Step.RUNNING, missing(exploration));

        exploration.stop(CONTEXT);

        assertTrue(factory.last.stopped, "a cancelled job stops its search");
        assertFalse(exploration.searching());
    }

    private static MineBlocksTask.Exploration.Step missing(MineBlocksTask.Exploration exploration) {
        return missing(exploration, 100);
    }

    private static MineBlocksTask.Exploration.Step missing(MineBlocksTask.Exploration exploration, int maxTicks) {
        return exploration.missing(CONTEXT, CALLER, "minecraft:stone", 16, maxTicks);
    }

    private static final class RecordingFactory implements BlockSearchTaskFactory {
        private final TaskStatus result;
        int created;
        boolean unavailable;
        boolean throwing;
        String summary = "";
        Caller lastCaller;
        ScriptedTask last;

        RecordingFactory(TaskStatus result) {
            this.result = result;
        }

        @Override
        public Task create(CompanionEntity companion, Caller caller, String blockOrTag, int radius, int maxTicks) {
            created++;
            lastCaller = caller;
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
