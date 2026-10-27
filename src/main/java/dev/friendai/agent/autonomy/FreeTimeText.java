package dev.friendai.agent.autonomy;

import java.util.List;

/** The model-facing lines about free time. */
public final class FreeTimeText {
    private FreeTimeText() {}

    /** The state-block line, empty while free time is off so ordinary prompts stay short. */
    public static String stateLine(FreeTime state, long nowMillis) {
        if (!state.on()) {
            return "";
        }
        long minutes = Math.max(0, nowMillis - state.sinceMillis()) / 60_000;
        return "Free time: on for " + minutes + " min; "
                + (state.shift() == null ? "between activities" : "current activity: " + state.shift().goal());
    }

    /** The last shifts, newest first, one short line each. */
    public static List<String> recent(FreeTime state) {
        return state.history().stream()
                .map(r -> r.goal() + ": " + (r.success() ? "ok" : "failed") + (r.summary().isBlank() ? "" : ", "
                        + FreeTime.cut(r.summary(), 120)))
                .toList();
    }
}
