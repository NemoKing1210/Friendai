package dev.friendai.agent.turn;

import dev.friendai.agent.autonomy.Shift;
import dev.friendai.agent.autonomy.ShiftRecord;
import dev.friendai.agent.autonomy.ShiftStopWhen;
import java.util.List;
import java.util.Locale;

/** Small deterministic rules for whether routine autonomous work needs an owner-facing line. */
public final class CompanionSpeechPolicy {
    public static final long AUTHORITY_QUESTION_COOLDOWN_MILLIS = 5 * 60_000L;

    private CompanionSpeechPolicy() {}

    /** Player messages always earn a reply; outcome-only turns may use tools for learning without narration. */
    public static boolean maySpeak(List<BrainEvent> events, boolean owesPlayerReply) {
        return owesPlayerReply || events.stream().anyMatch(event -> !(event instanceof BrainEvent.SkillOutcome));
    }

    /** Cancellation is already kept in task history and does not need a model turn by itself. */
    public static boolean needsModelTurn(BrainEvent event) {
        return !(event instanceof BrainEvent.TaskCancelled);
    }

    /** An unchanged authority blocker gets one owner-facing model turn per cooldown. */
    public static boolean mayAskAuthorityQuestion(
            BrainEvent.PlanBlocked event, long nowMillis, long lastAskedAtMillis) {
        if (event == null) {
            return false;
        }
        return lastAskedAtMillis == Long.MIN_VALUE
                || (nowMillis >= lastAskedAtMillis
                        && nowMillis - lastAskedAtMillis >= AUTHORITY_QUESTION_COOLDOWN_MILLIS);
    }

    /** Stable key for the same blocked action and reason across duplicate queue notifications. */
    public static String authorityQuestionKey(BrainEvent.PlanBlocked event) {
        return normalize(event.toolName()) + "|" + normalize(event.reason());
    }

    /** Successful work is already visible in history; repeat a failure only when its blocker changed. */
    public static boolean shouldReportShiftFailure(
            Shift shift, String summary, ShiftStopWhen stopWhen, List<ShiftRecord> history) {
        if (shift == null
                || summary == null
                || summary.isBlank()
                || stopWhen == ShiftStopWhen.OWNER_BACK
                || stopWhen == ShiftStopWhen.OWNER_SPOKE
                || stopWhen == ShiftStopWhen.OWNER_PAUSED) {
            return false;
        }
        String blocker = normalize(summary);
        return history.stream()
                .filter(record -> !record.success() && record.id() != shift.id())
                .noneMatch(record -> normalize(record.summary()).equals(blocker));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
