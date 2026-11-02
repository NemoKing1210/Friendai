package dev.friendai.agent.autonomy;

/**
 * How a finished shift went, kept so the director does not repeat itself.
 *
 * @param stopWhen the condition that actually ended it, recorded for the audit
 */
public record ShiftRecord(
        long id,
        String goal,
        NeedKind need,
        boolean success,
        String summary,
        long endedMillis,
        ShiftStopWhen stopWhen) {

    public ShiftRecord(long id, String goal, NeedKind need, boolean success, String summary, long endedMillis) {
        this(id, goal, need, success, summary, endedMillis, ShiftStopWhen.DONE);
    }

    public ShiftRecord {
        goal = goal == null ? "" : goal;
        summary = summary == null ? "" : summary;
        stopWhen = stopWhen == null ? ShiftStopWhen.DONE : stopWhen;
    }
}
