package dev.friendai.features.acquisition.minecraft.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObtainNestedLifecycleTest {
    private static final class ProbeTask implements Task {
        private final List<String> lifecycle = new ArrayList<>();

        @Override
        public String type() {
            return "probe";
        }

        @Override
        public void start(TaskContext context) {}

        @Override
        public TaskStatus tick(TaskContext context) {
            return TaskStatus.SUCCESS;
        }

        @Override
        public String describe() {
            return "probe child";
        }

        @Override
        public String resultSummary() {
            return "done";
        }

        @Override
        public void stop(TaskContext context) {
            lifecycle.add("stop");
        }

        @Override
        public void onTerminal(TaskContext context) {
            lifecycle.add("terminal");
        }
    }

    @Test
    void naturalChildSettlementStopsThenRunsTerminalHook() {
        ProbeTask child = new ProbeTask();

        ObtainTask.safeTerminate(child, null);

        assertEquals(List.of("stop", "terminal"), child.lifecycle);
    }

    @Test
    void cancellationStopsChildWithoutRunningTerminalHook() {
        ProbeTask child = new ProbeTask();

        ObtainTask.safeStop(child, null);

        assertEquals(List.of("stop"), child.lifecycle);
    }
}
