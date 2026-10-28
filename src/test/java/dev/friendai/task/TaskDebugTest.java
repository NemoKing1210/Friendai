package dev.friendai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.debug.DebugAction;
import dev.friendai.debug.WorkCell;
import dev.friendai.debug.WorkKind;
import dev.friendai.debug.WorkSet;
import dev.friendai.debug.WorkStatus;
import dev.friendai.task.plan.PlanEntry;
import dev.friendai.task.plan.SequenceTask;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskDebugTest {
    private static final WorkSet WORK = new WorkSet(
            "sample",
            WorkKind.BLOCKS,
            1,
            0,
            1,
            0,
            0,
            false,
            List.of(new WorkCell(1, 2, 3, DebugAction.BREAK, WorkStatus.NEXT, "sample")));

    @Test
    void ordinary_task_has_no_work_set_by_default() {
        assertEquals(List.of(), new EmptyTask().debugWorkSets());
    }

    @Test
    void sequence_exposes_only_its_active_step_work_set() {
        Task child = new WorkTask();
        SequenceTask sequence = new SequenceTask("goal", List.of(new PlanEntry("sample", ignored -> child)), () -> 100);
        assertEquals(List.of(), sequence.debugWorkSets());
        sequence.start(null);
        sequence.tick(null);
        assertEquals(List.of(WORK), sequence.debugWorkSets());
    }

    private static class EmptyTask implements Task {
        public String type() {
            return "empty";
        }

        public void start(TaskContext c) {}

        public TaskStatus tick(TaskContext c) {
            return TaskStatus.RUNNING;
        }

        public void stop(TaskContext c) {}

        public String describe() {
            return "empty";
        }

        public String resultSummary() {
            return "";
        }
    }

    private static final class WorkTask extends EmptyTask {
        @Override
        public List<WorkSet> debugWorkSets() {
            return List.of(WORK);
        }
    }
}
