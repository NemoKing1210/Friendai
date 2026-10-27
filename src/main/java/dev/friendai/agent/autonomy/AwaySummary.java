package dev.friendai.agent.autonomy;

import java.util.ArrayList;
import java.util.List;

/** Lines saved for an owner who was not there to hear them, told once when they come back. */
public final class AwaySummary {
    private AwaySummary() {}

    public record Taken(String text, FreeTime state) {}

    /** Appends a line, keeping the newest {@link FreeTime#MAX_AWAY}. */
    public static FreeTime add(FreeTime state, String line) {
        if (line == null || line.isBlank()) {
            return state;
        }
        List<String> away = new ArrayList<>(state.away());
        away.add(line.strip());
        return withAway(state, away);
    }

    /** The saved lines joined into one, and the state without them. */
    public static Taken take(FreeTime state) {
        return new Taken(String.join("; ", state.away()), withAway(state, List.of()));
    }

    private static FreeTime withAway(FreeTime s, List<String> away) {
        return new FreeTime(s.on(), s.origin(), s.wish(), s.sinceMillis(), s.shift(), s.awaitingChoice(),
                s.nextShiftId(), s.history(), s.lessons(), s.cooldownUntil(), away);
    }
}
