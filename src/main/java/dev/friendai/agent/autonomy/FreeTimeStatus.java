package dev.friendai.agent.autonomy;

/** What free time is doing, why, what it will decide next, and what the budget allows. Shown to the owner. */
public record FreeTimeStatus(boolean on, String activity, String why, String next, String budget, String last) {

    public static final int MAX_ACTIVITY = 200;
    public static final int MAX_TEXT = 160;

    public FreeTimeStatus {
        activity = cut(activity, MAX_ACTIVITY);
        why = cut(why, MAX_ACTIVITY);
        next = cut(next, MAX_TEXT);
        budget = cut(budget, 80);
        last = cut(last, MAX_ACTIVITY);
    }

    public static FreeTimeStatus off() {
        return new FreeTimeStatus(false, "", "", "", "", "");
    }

    /**
     * @param used director turns in the rolling hour
     * @param perHour the cap for the owner's online state
     * @param failStreak unanswered director turns, which back the next one off
     */
    public static FreeTimeStatus of(FreeTime state, String next, int used, int perHour, int failStreak) {
        if (!state.on()) {
            return off();
        }
        Shift shift = state.shift();
        String activity = shift == null ? "" : shift.goal();
        String why = shift == null ? "" : shift.why();
        String last = state.history().isEmpty() ? "" : lastLine(state.history().getFirst());
        String budget = used + "/" + Math.max(1, perHour) + " director turns this hour"
                + (failStreak > 0 ? ", backoff " + failStreak : "");
        return new FreeTimeStatus(state.on(), activity, why, next, budget, last);
    }

    private static String lastLine(ShiftRecord record) {
        return record.goal() + ": " + (record.success() ? "ok" : "failed")
                + (record.summary().isBlank() ? "" : " (" + record.summary() + ")");
    }

    private static String cut(String text, int max) {
        String value = text == null ? "" : text.strip();
        return value.length() <= max ? value : value.substring(0, max);
    }
}
