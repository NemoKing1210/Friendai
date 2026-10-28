package dev.friendai.server.execution;

import dev.friendai.features.places.PlaceCandidate;
import dev.friendai.task.TaskStatus;

/** Server-thread destination for task history, brain and world-book events. */
public interface TaskEventSink {
    void onFinished(int id, String toolName, TaskStatus status, String summary);

    void onCancelled(int id, String toolName, String reason);

    default void onJobFinished(long jobId, long planId, String toolName, TaskStatus status, String summary) {}

    default void onPlanFinished(long planId, String goal, boolean success, String summary) {}

    default void onPlanBlocked(long planId, String goal, long jobId, String toolName, String reason, int remaining) {}

    default void onNotice(String text) {}

    default void onPlace(PlaceCandidate candidate, String toolName) {}

    default void onDebug(String text) {}
}
