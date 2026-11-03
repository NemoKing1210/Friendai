package dev.friendai.agent.autonomy;

import dev.friendai.agent.recover.FailureKind;
import dev.friendai.agent.recover.RecoveryAdvisor;
import dev.friendai.foundation.text.UserMessage;
import java.util.List;
import java.util.Set;

/** The model-facing lines about free time. */
public final class FreeTimeText {
    private FreeTimeText() {}

    /** The state-block line, empty while free time is off so ordinary prompts stay short. */
    public static String stateLine(FreeTime state, long nowMillis) {
        if (!state.on()) {
            return "";
        }
        long minutes = Math.max(0, nowMillis - state.sinceMillis()) / 60_000;
        Shift shift = state.shift();
        return "Free time: on for " + minutes + " min; "
                + (shift == null
                        ? "between activities"
                        : "current activity: " + shift.goal() + " (purpose: " + shift.purpose() + "; budget: "
                                + shift.budget() + "; stops when "
                                + shift.stopWhen().phrase() + ")");
    }

    /** The audit's note for a shift that ended for a reason other than its own work finishing, or empty. */
    public static String stopNote(ShiftRecord record) {
        return record.stopWhen() == ShiftStopWhen.DONE
                ? ""
                : " [" + record.stopWhen().name() + "]";
    }

    /** A concise translated owner line for known failures; diagnostics stay in free-time history. */
    public static UserMessage shiftFailureMessage(String summary) {
        if (summary != null && summary.contains("storage is full")) {
            return UserMessage.of("friendai.freetime.storage_full");
        }
        var diagnosis = new RecoveryAdvisor().diagnose("", summary, Set.of());
        if (diagnosis.kind() == FailureKind.MISSING_ITEM && diagnosis.detail().startsWith("missing scaffold blocks:")) {
            return UserMessage.of("friendai.freetime.shift_report.scaffold_needed");
        }
        if (diagnosis.kind() == FailureKind.RESOURCE_NOT_FOUND
                && diagnosis.detail().equals("no food source in the local search area")) {
            return UserMessage.of("friendai.freetime.shift_report.food_not_found");
        }
        if (diagnosis.kind() == FailureKind.UNREACHABLE && diagnosis.detail().startsWith("missing support at")) {
            return UserMessage.of("friendai.freetime.shift_report.support_needed");
        }
        if (diagnosis.kind() == FailureKind.RESOURCE_NOT_FOUND
                && diagnosis.detail().startsWith("protected by the place ")) {
            return UserMessage.of(
                    "friendai.freetime.shift_report.place_protected",
                    diagnosis.detail().substring("protected by the place ".length()));
        }
        if (diagnosis.kind() == FailureKind.UNREACHABLE && diagnosis.detail().equals("no way up the tree")) {
            return UserMessage.of("friendai.freetime.shift_report.tree_unreachable");
        }
        return UserMessage.of("friendai.freetime.shift_report.failed");
    }

    /** An English diagnostic for tests and internal views; player-facing shift reports use translation keys. */
    public static String endReport(Shift shift, boolean success, String summary, ShiftStopWhen stopWhen) {
        ShiftStopWhen stop = stopWhen == null ? ShiftStopWhen.DONE : stopWhen;
        String purpose = shift == null ? ShiftContract.UNNAMED : shift.purpose();
        String done = summary == null || summary.isBlank()
                ? (success ? "done" : "nothing came of it")
                : FreeTime.cut(summary.strip(), 160);
        StringBuilder line =
                new StringBuilder("Free time: ").append(purpose).append(" — ").append(done);
        if (!done.equals(stop.phrase())) {
            line.append("; ").append(stop.phrase());
        }
        return line.append('.').toString();
    }

    /** The last shifts, newest first, one short line each. */
    public static List<String> recent(FreeTime state) {
        return state.history().stream()
                .map(r -> r.goal() + ": " + (r.success() ? "ok" : "failed")
                        + (r.summary().isBlank() ? "" : ", " + FreeTime.cut(r.summary(), 120)) + stopNote(r))
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
