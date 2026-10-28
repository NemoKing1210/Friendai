package dev.friendai.agent.autonomy;

import java.util.Optional;

/** What one finished shift means: its record, what the director should learn, and what is worth saying. */
public final class ShiftOutcome {
    /** The queue's summary for a plan that hit its time limit. */
    public static final String TIME_UP = "ran out of time";

    private ShiftOutcome() {}

    /**
     * @param notable the stage reached, when the shift moved the companion up the ladder
     * @param cooldownUntil when the shift's need may come back, for a failed shift
     */
    public record Result(ShiftRecord record, Optional<String> lesson, Optional<Long> cooldownUntil,
                         Optional<String> notable) {}

    /**
     * @param stageBefore the ladder stage when the shift began
     * @param diagnosis the recovery advisor's line for a failure, or empty
     */
    public static Result of(Shift shift, boolean success, String summary, String stageBefore, String stageAfter,
                            String diagnosis, long nowMillis, AutonomySettings s) {
        String text = summary == null ? "" : summary;
        ShiftRecord record = new ShiftRecord(shift.id(), shift.goal(), shift.need(), success, text, nowMillis);
        Optional<String> notable = !stageAfter.equals(stageBefore) ? Optional.of(stageAfter) : Optional.empty();
        if (success || text.contains(TIME_UP)) {
            return new Result(record, Optional.empty(), Optional.empty(), notable);
        }
        Optional<Long> cooldown = Optional.of(nowMillis + s.failCooldownMin() * 60_000L);
        if (shift.reflex()) {
            return new Result(record, Optional.empty(), cooldown, notable);
        }
        String why = diagnosis == null || diagnosis.isBlank() ? text : diagnosis;
        return new Result(record, Optional.of(FreeTime.cut(shift.goal() + ": " + why, FreeTime.MAX_LESSON)), cooldown,
                notable);
    }
}
