package dev.friendai.server.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.friendai.foundation.diagnostics.SecretRedactor;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActiveTaskCrashSummaryTest {
    @Test
    void aThrownTickReportsTheExceptionClassAndMessage() {
        var completion = crashOf(new IllegalStateException("block state vanished"));

        assertEquals(TaskStatus.FAILED, completion.status());
        assertEquals("task crashed: IllegalStateException: block state vanished", completion.summary());
    }

    @Test
    void aCrashWithoutAMessageStillNamesTheClass() {
        var completion = crashOf(new IllegalStateException());

        assertEquals("task crashed: IllegalStateException: no message", completion.summary());
    }

    @Test
    void aConfiguredSecretInTheMessageIsRedacted() {
        ActiveTaskController controller = new ActiveTaskController(() -> 300);
        controller.setRedactor(SecretRedactor.of(() -> List.of("sk-live-123")));

        var completion = crashOf(controller, new IllegalStateException("request rejected for sk-live-123"));

        assertEquals("task crashed: IllegalStateException: request rejected for ***", completion.summary());
    }

    @Test
    void aLongMessageIsBoundedToTheSummaryLimit() {
        var completion = crashOf(new IllegalStateException("y".repeat(400)));

        assertEquals(
                "task crashed: IllegalStateException: " + "y".repeat(ActiveTaskController.MAX_CRASH_MESSAGE_CHARS),
                completion.summary());
    }

    @Test
    void aMultiLineMessageStaysOneSummaryLine() {
        var completion = crashOf(new IllegalStateException("first line\nsecond line"));

        assertEquals("task crashed: IllegalStateException: first line second line", completion.summary());
    }

    @Test
    void aSecretIsRedactedBeforeTheMessageIsCut() {
        ActiveTaskController controller = new ActiveTaskController(() -> 300);
        controller.setRedactor(SecretRedactor.of(() -> List.of("sk-live-123")));
        // The secret starts at offset 150, so a 160-character cut taken before redaction would slice it in half.
        String message = "a".repeat(150) + "sk-live-123" + "b".repeat(400);

        var completion = crashOf(controller, new IllegalStateException(message));

        assertEquals(
                "task crashed: IllegalStateException: " + "a".repeat(150) + "***" + "b".repeat(7),
                completion.summary());
        assertFalse(completion.summary().contains("sk-live"), "a cut secret must never survive the summary");
    }

    @Test
    void aSecretSpanningALineBreakIsStillMasked() {
        ActiveTaskController controller = new ActiveTaskController(() -> 300);
        controller.setRedactor(SecretRedactor.of(() -> List.of("sk-\nsecret")));

        var completion = crashOf(controller, new IllegalStateException("bad sk-\nsecret end"));

        assertEquals("task crashed: IllegalStateException: bad *** end", completion.summary());
    }

    @Test
    void theCutNeverSplitsASurrogatePair() {
        String emoji = "😀";
        var completion = crashOf(new IllegalStateException("a".repeat(159) + emoji + "b".repeat(10)));

        assertEquals("task crashed: IllegalStateException: " + "a".repeat(159) + emoji, completion.summary());
    }

    @Test
    void aRedactorThatThrowsDegradesToTheClassAlone() {
        ActiveTaskController controller = new ActiveTaskController(() -> 300);
        controller.setRedactor(text -> {
            throw new IllegalStateException("redactor failed");
        });

        var completion = crashOf(controller, new IllegalStateException("some detail"));

        assertEquals("task crashed: IllegalStateException: no message", completion.summary());
    }

    private ActiveTaskController.Completion crashOf(RuntimeException crash) {
        return crashOf(new ActiveTaskController(() -> 300), crash);
    }

    private ActiveTaskController.Completion crashOf(ActiveTaskController controller, RuntimeException crash) {
        TaskContext context = new TaskContext(null, null, null, null, null);
        controller.start(
                new ThrowingTask(crash),
                new TaskStartContext(
                        "mine_blocks",
                        null,
                        null,
                        true,
                        false,
                        false,
                        1,
                        0,
                        0,
                        false,
                        ServerSettings.DEFAULTS,
                        context,
                        null,
                        "test"));
        return controller.tick(context).orElseThrow();
    }

    private static final class ThrowingTask implements Task {
        private final RuntimeException crash;

        ThrowingTask(RuntimeException crash) {
            this.crash = crash;
        }

        @Override
        public String type() {
            return "throwing_task";
        }

        @Override
        public void start(TaskContext context) {}

        @Override
        public TaskStatus tick(TaskContext context) {
            throw crash;
        }

        @Override
        public void stop(TaskContext context) {}

        @Override
        public String describe() {
            return "throwing task";
        }

        @Override
        public String resultSummary() {
            return "crashed";
        }
    }
}
