package dev.friendai.server.execution;

import dev.friendai.execution.TaskDisposition;
import dev.friendai.execution.TaskWorkResult;
import dev.friendai.execution.log.TaskLogEvent;
import dev.friendai.features.places.PlaceCandidate;
import dev.friendai.task.TaskStatus;
import java.util.Optional;

/** Server-thread destination for task history, brain and world-book events. */
public interface TaskEventSink {
    void onFinished(int id, String toolName, TaskStatus status, String summary);

    /**
     * The same notification with the correlation the caller already knows: the plan the task belonged to, or 0 for a
     * standalone job. A listener that does not care keeps the four-argument form.
     */
    default void onFinished(int id, long planId, String toolName, TaskStatus status, String summary) {
        onFinished(id, toolName, status, summary);
    }

    /** A task outcome with measured work; legacy sinks keep receiving the existing summary callback. */
    default void onFinished(
            int id,
            long planId,
            String toolName,
            TaskStatus status,
            String summary,
            Optional<TaskWorkResult> workResult) {
        onFinished(id, planId, toolName, status, summary);
    }

    /** Typed terminal result with execution identity; legacy sinks receive the original status callback. */
    default void onFinished(
            int id,
            long planId,
            String toolName,
            TaskDisposition disposition,
            String summary,
            Optional<TaskWorkResult> workResult,
            long execution) {
        onFinished(id, planId, toolName, status(disposition), summary, workResult);
    }

    void onCancelled(int id, String toolName, String reason);

    /** Typed cancellation with execution identity; existing sinks retain their cancellation callback. */
    default void onCancelled(int id, long execution, String toolName, String reason) {
        onCancelled(id, toolName, reason);
    }

    default void onJobFinished(long jobId, long planId, String toolName, TaskStatus status, String summary) {}

    /** Typed job settlement; execution is zero when the job never started. */
    default void onJobFinished(
            long jobId, long planId, String toolName, TaskDisposition disposition, String summary, long execution) {
        onJobFinished(jobId, planId, toolName, status(disposition), summary);
    }

    /** The receipt belongs to this activation and is available for its settlement diagnostics. */
    default void onJobFinished(
            long jobId,
            long planId,
            String toolName,
            TaskDisposition disposition,
            String summary,
            long execution,
            Optional<TaskWorkResult> workResult) {
        onJobFinished(jobId, planId, toolName, disposition, summary, execution);
    }

    default void onPlanFinished(long planId, String goal, boolean success, String summary) {}

    default void onPlanFinished(long planId, String goal, TaskDisposition disposition, String summary) {
        onPlanFinished(planId, goal, disposition == TaskDisposition.SUCCESS, summary);
    }

    default void onPlanBlocked(long planId, String goal, long jobId, String toolName, String reason, int remaining) {}

    /**
     * The same notification, saying whether {@code remaining} is the whole path or a lower bound a conditional plan
     * could not settle. A listener that does not care keeps the six-argument form.
     */
    default void onPlanBlocked(
            long planId,
            String goal,
            long jobId,
            String toolName,
            String reason,
            int remaining,
            boolean remainingExact) {
        onPlanBlocked(planId, goal, jobId, toolName, reason, remaining);
    }

    default void onNotice(String text) {}

    default void onPlace(PlaceCandidate candidate, String toolName) {}

    default void onDebug(String text) {}

    /**
     * One structured diagnostic lifecycle fact (start, terminal, hold, resume). The default ignores it, so existing
     * sinks and tests keep working; a sink that publishes it must not also duplicate the legacy callbacks.
     */
    default void onDiagnostic(TaskLogEvent event) {}

    private static TaskStatus status(TaskDisposition disposition) {
        return disposition == TaskDisposition.SUCCESS ? TaskStatus.SUCCESS : TaskStatus.FAILED;
    }
}
