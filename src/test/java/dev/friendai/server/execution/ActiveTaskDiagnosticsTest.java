package dev.friendai.server.execution;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.execution.TaskWorkResult;
import dev.friendai.execution.log.TaskLogEvent;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.task.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ActiveTaskDiagnosticsTest {
    private final TaskContext context = new TaskContext(null, null, null, null, null);
    private final ActiveTaskController controller = new ActiveTaskController(() -> 300);
    private final List<TaskLogEvent> events = new ArrayList<>();

    private void start(FakeTask task, int id) {
        controller.setDiagnostics(events::add);
        controller.start(
                task,
                new TaskStartContext(
                        "work",
                        null,
                        null,
                        true,
                        false,
                        false,
                        id,
                        9,
                        3,
                        false,
                        ServerSettings.DEFAULTS,
                        context,
                        null,
                        "test"));
    }

    private void ticks(int count) {
        for (int i = 0; i < count; i++) controller.tick(context);
    }

    private List<TaskLogEvent> samples() {
        return events.stream()
                .filter(e -> e.event().equals("task.progress") || e.event().equals("task.phase"))
                .toList();
    }

    @Test
    void changingDetailDoesNotResetSamples() {
        FakeTask task = new FakeTask();
        start(task, 1);
        ticks(1);
        assertEquals(1, samples().size());
        for (int i = 0; i < 199; i++) {
            task.detail = "changed " + i;
            ticks(1);
        }
        assertEquals(1, task.reads);
        ticks(1);
        assertEquals(2, samples().size());
        assertEquals("no_progress", samples().get(1).facts().get("reason"));
        assertEquals("changed 198", samples().get(1).facts().get("detail"));
        ticks(200);
        assertEquals(3, samples().size());
        assertTrue(events.stream().noneMatch(e -> e.event().equals("task.snapshot")));
    }

    @Test
    void movedStampUsesHundredTickCadenceAndWaitingTransitionsAreImmediate() {
        FakeTask task = new FakeTask();
        start(task, 1);
        ticks(1);
        task.stamp = 2;
        ticks(99);
        assertEquals(1, task.reads);
        ticks(1);
        assertEquals(2, samples().size());
        task.wait = 7;
        ticks(1);
        var entry = samples().getLast();
        assertEquals("task.phase", entry.event());
        assertEquals("waiting", entry.facts().get("phase"));
        assertEquals("7", entry.facts().get("waitingTicks"));
        assertEquals("1000", entry.facts().get("waitingLimitTicks"));
        assertEquals("2", entry.facts().get("stamp"));
        ticks(199);
        assertEquals(3, samples().size());
        ticks(1);
        assertEquals("task.progress", samples().getLast().event());
        assertEquals("waiting", samples().getLast().facts().get("reason"));
        task.wait = -1;
        ticks(1);
        assertEquals("running", samples().getLast().facts().get("reason"));
    }

    @Test
    void aProgressHeartbeatWaitsAfterANoProgressSample() {
        FakeTask task = new FakeTask();
        start(task, 1);
        ticks(201);
        assertEquals(2, samples().size());
        task.stamp = 2;
        ticks(99);
        assertEquals(2, samples().size());
        assertEquals(2, task.reads);
        ticks(1);
        assertEquals(3, samples().size());
        assertEquals("running", samples().getLast().facts().get("reason"));
    }

    @Test
    void aDueTickReadsDetailOnce() {
        FakeTask task = new FakeTask();
        start(task, 1);
        ticks(201);
        assertEquals(2, task.reads);
        assertEquals(2, samples().size());
    }

    @Test
    void frozenTicksDoNotAdvanceHeartbeat() {
        FakeTask task = new FakeTask();
        start(task, 1);
        ticks(51);
        controller.freezeCurrent();
        FakeTask interrupt = new FakeTask();
        start(interrupt, 2);
        ticks(250);
        controller.stop(new StopRequest("test"), context);
        controller.resumeFrozen(controller.takeFrozen().orElseThrow());
        events.clear();
        ticks(149);
        assertEquals(0, samples().size());
        ticks(1);
        assertEquals(1, samples().size());
    }

    @Test
    void blankAndNullDetailFallBackToDescription() {
        for (String detail : new String[] {null, " "}) {
            FakeTask task = new FakeTask();
            task.detail = detail;
            start(task, 1);
            ticks(1);
            assertEquals("description", samples().getLast().facts().get("detail"));
        }
    }

    @Test
    void aReentrantReplacementDoesNotReportTheOldActivation() {
        FakeTask task = new FakeTask();
        task.onTick = () -> start(new FakeTask(), 2);
        start(task, 1);
        ticks(1);
        assertTrue(samples().isEmpty());
        assertTrue(events.stream().noneMatch(e -> e.event().equals("task.finish")));
    }

    @Test
    void stallCapturesDetailBeforeStopClearsIt() {
        FakeTask task = new FakeTask();
        start(task, 1);
        ticks(3000);
        var completion = controller.tick(context).orElseThrow();
        var snapshots =
                events.stream().filter(e -> e.event().equals("task.snapshot")).toList();
        assertEquals(1, snapshots.size());
        assertEquals("full status", snapshots.getFirst().facts().get("phase"));
        assertTrue(completion.summary().contains("full status"));
        assertEquals("task.finish", events.getLast().event());
    }

    @Test
    void terminalLogAndCompletionCaptureTheResultBeforeCleanup() {
        FakeTask task = new FakeTask() {
            String result = "delivered four tools";

            @Override
            public TaskStatus tick(TaskContext c) {
                return TaskStatus.SUCCESS;
            }

            @Override
            public String resultSummary() {
                return result;
            }

            @Override
            public void onTerminal(TaskContext c) {
                result = "";
            }
        };
        start(task, 1);
        var completion = controller.tick(context).orElseThrow();
        assertEquals("delivered four tools", completion.summary());
        assertEquals("delivered four tools", events.getLast().facts().get("summary"));
        assertEquals("", task.resultSummary());
    }

    @Test
    void terminalWorkResultSurvivesCleanupThatClearsChildState() {
        TaskWorkResult result = new TaskWorkResult(
                List.of(new TaskWorkResult.Line("minecraft:oak_log", 12, 9, TaskWorkResult.Evidence.CARRIED)),
                "partial",
                "2 logs remain on the crown",
                "try the next tree");
        FakeTask task = new FakeTask() {
            TaskWorkResult childResult = result;

            @Override
            public TaskStatus tick(TaskContext c) {
                return TaskStatus.SUCCESS;
            }

            @Override
            public Optional<TaskWorkResult> workResult() {
                return Optional.ofNullable(childResult);
            }

            @Override
            public void onTerminal(TaskContext c) {
                childResult = null;
            }
        };

        start(task, 1);
        var completion = controller.tick(context).orElseThrow();

        assertEquals(Optional.of(result), completion.workResult());
        assertEquals(result.compact(), events.getLast().facts().get("workResult"));
        assertTrue(task.workResult().isEmpty());
    }

    @Test
    void tickCarriesTheSameExecutionIdentityAsStart() {
        List<dev.friendai.foundation.diagnostics.LogContext> observed = new ArrayList<>();
        FakeTask task = new FakeTask() {
            @Override
            public void start(TaskContext c) {
                observed.add(c.logContext());
            }

            @Override
            public TaskStatus tick(TaskContext c) {
                observed.add(c.logContext());
                return TaskStatus.RUNNING;
            }
        };
        start(task, 1);
        ticks(1);
        assertTrue(observed.getFirst().execution() > 0);
        assertEquals(observed.getFirst(), observed.getLast());
    }

    private static class FakeTask implements Task {
        long stamp = 1;
        int wait = -1;
        int reads;
        String detail = "full status";
        Runnable onTick;

        public String type() {
            return "fake";
        }

        public void start(TaskContext c) {}

        public TaskStatus tick(TaskContext c) {
            if (onTick != null) onTick.run();
            return TaskStatus.RUNNING;
        }

        public void stop(TaskContext c) {
            detail = "cleared";
        }

        public String describe() {
            return "description";
        }

        public String resultSummary() {
            return "result";
        }

        public String debugStatus() {
            reads++;
            return detail;
        }

        public long progressStamp() {
            return stamp;
        }

        public int waitingTicks() {
            return wait;
        }

        public int waitingLimitTicks() {
            return 1000;
        }
    }
}
