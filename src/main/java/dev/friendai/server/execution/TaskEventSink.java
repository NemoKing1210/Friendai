package dev.friendai.server.execution;

import dev.friendai.features.places.PlaceCandidate;
import dev.friendai.task.TaskStatus;

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

    void onCancelled(int id, String toolName, String reason);

    default void onJobFinished(long jobId, long planId, String toolName, TaskStatus status, String summary) {}

    default void onPlanFinished(long planId, String goal, boolean success, String summary) {}

    default void onPlanBlocked(long planId, String goal, long jobId, String toolName, String reason, int remaining) {}

    default void onNotice(String text) {}

    default void onPlace(PlaceCandidate candidate, String toolName) {}

    default void onDebug(String text) {}
}
