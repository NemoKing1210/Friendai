package dev.friendai.execution.queue;

public record PlanRecord(
        long id,
        String goal,
        int priority,
        long addedTick,
        long deadlineTick,
        FailurePolicy onFailure,
        PlanDefinition definition,
        PlanCursor cursor,
        boolean autoRepair) {
    public PlanRecord(long id, String goal, int priority, long addedTick, long deadlineTick, FailurePolicy onFailure) {
        this(id, goal, priority, addedTick, deadlineTick, onFailure, null, null, true);
    }

    /** A plan without repair consent information defaults to on, preserving old saves and callers. */
    public PlanRecord(
            long id,
            String goal,
            int priority,
            long addedTick,
            long deadlineTick,
            FailurePolicy onFailure,
            PlanDefinition definition,
            PlanCursor cursor) {
        this(id, goal, priority, addedTick, deadlineTick, onFailure, definition, cursor, true);
    }

    public PlanRecord {
        goal = goal == null ? "" : goal;
    }

    public PlanRecord withCursor(PlanCursor updated) {
        return new PlanRecord(id, goal, priority, addedTick, deadlineTick, onFailure, definition, updated, autoRepair);
    }
}
