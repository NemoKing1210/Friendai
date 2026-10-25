package dev.friendai.debug;

import java.util.List;

/**
 * One job of the companion's queue as the menu draws it in the chain view.
 * {@code planId} is 0 for a direct job; {@code state} is the lower-case {@code JobState} name, with
 * {@code blocked} for a pending job that cannot start yet and {@code ready} for one that can.
 */
public record QueueRow(
        long id,
        long planId,
        String planGoal,
        String planStatus,
        String tool,
        String args,
        String state,
        String note,
        List<Long> after,
        int attempts,
        int maxAttempts,
        int priority) {
    public QueueRow {
        planGoal = planGoal == null ? "" : planGoal;
        planStatus = planStatus == null ? "" : planStatus;
        tool = tool == null ? "" : tool;
        args = args == null ? "" : args;
        state = state == null ? "" : state;
        note = note == null ? "" : note;
        after = after == null ? List.of() : List.copyOf(after);
    }
}
