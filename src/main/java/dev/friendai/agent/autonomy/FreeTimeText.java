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
                + (state.shift() == null
                        ? "between activities"
                        : "current activity: " + state.shift().goal());
    }

    /** The last shifts, newest first, one short line each. */
    public static List<String> recent(FreeTime state) {
        return state.history().stream()
                .map(r -> r.goal() + ": " + (r.success() ? "ok" : "failed")
                        + (r.summary().isBlank() ? "" : ", " + FreeTime.cut(r.summary(), 120)))
                .toList();
    }

    /** The next decision in words, for the owner's status and the audit. */
    public static String decisionLabel(ShiftPolicy.Decision decision) {
        if (decision instanceof ShiftPolicy.Reflex reflex) {
            return "reflex: " + reflex.need().kind();
        }
        if (decision instanceof ShiftPolicy.Fallback fallback) {
            return "fallback: " + fallback.need().kind();
        }
        if (decision instanceof ShiftPolicy.Director) {
            return "director";
        }
        if (decision instanceof ShiftPolicy.Expired) {
            return "close expired shift";
        }
        if (decision instanceof ShiftPolicy.CloseStale) {
            return "close stale shift";
        }
        if (decision instanceof ShiftPolicy.None) {
            return "waiting";
        }
        if (decision instanceof ShiftPolicy.WaitBudget) {
            return "waiting for budget";
        }
        return "waiting";
    }

    /** One English line for the model or the audit; the owner-facing command builds its own components. */
    public static String statusLine(FreeTimeStatus status) {
        if (!status.on()) {
            return "Free time: off.";
        }
        StringBuilder sb = new StringBuilder("Free time: on");
        if (!status.activity().isBlank()) {
            sb.append("; activity: ").append(status.activity());
            if (!status.why().isBlank()) {
                sb.append(" (").append(status.why()).append(')');
            }
        } else {
            sb.append("; between activities");
        }
        if (!status.next().isBlank()) {
            sb.append("; next: ").append(status.next());
        }
        if (!status.budget().isBlank()) {
            sb.append("; budget: ").append(status.budget());
        }
        if (!status.last().isBlank()) {
            sb.append("; last: ").append(status.last());
        }
        if (!status.plannerModel().isBlank()) {
            sb.append("; planner model: ").append(status.plannerModel());
        }
        return sb.append('.').toString();
    }
}
