package dev.friendai.task;

import dev.friendai.place.PlaceCandidate;

public interface TaskListener {
    void onFinished(int id, String toolName, TaskStatus status, String summary);

    void onCancelled(int id, String toolName, String reason);

    default void onJobFinished(long jobId, long planId, String toolName, TaskStatus status, String summary) {}

    default void onPlanFinished(long planId, String goal, boolean success, String summary) {}

    default void onPlanBlocked(long planId, String goal, long jobId, String toolName, String reason, int remaining) {}

    /** Something the companion noticed outside a task, like a furnace finishing. */
    default void onNotice(String text) {}

    /** A successful task noticed a place; the listener decides whether the owner's book takes it. */
    default void onPlace(PlaceCandidate candidate, String toolName) {}

    /** A line tasks write for the operator's debug tab: pathing decisions, stuck reasons. */
    default void onDebug(String text) {}
}
