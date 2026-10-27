package dev.friendai.agent.autonomy;

/** How a finished shift went, kept so the director does not repeat itself. */
public record ShiftRecord(long id, String goal, NeedKind need, boolean success, String summary, long endedMillis) {
    public ShiftRecord {
        goal = goal == null ? "" : goal;
        summary = summary == null ? "" : summary;
    }
}
