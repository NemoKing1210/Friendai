package dev.friendai.queue;

public record PlanRecord(long id, String goal, int priority, long addedTick, long deadlineTick, FailurePolicy onFailure) {
    public PlanRecord { goal = goal == null ? "" : goal; }
}
