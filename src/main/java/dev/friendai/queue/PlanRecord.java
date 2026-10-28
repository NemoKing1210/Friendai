package dev.friendai.queue;

public record PlanRecord(long id, String goal, int priority, long addedTick, long deadlineTick,
                         FailurePolicy onFailure, PlanDefinition definition, PlanCursor cursor) {
    public PlanRecord(long id, String goal, int priority, long addedTick, long deadlineTick, FailurePolicy onFailure) {
        this(id, goal, priority, addedTick, deadlineTick, onFailure, null, null);
    }
    public PlanRecord {
        goal = goal == null ? "" : goal;
    }
    public PlanRecord withCursor(PlanCursor updated) {
        return new PlanRecord(id, goal, priority, addedTick, deadlineTick, onFailure, definition, updated);
    }
}
