package dev.friendai.agent.turn;

import dev.friendai.agent.autonomy.Shift;
import dev.friendai.agent.autonomy.ShiftBlocker;
import dev.friendai.agent.autonomy.ShiftRecord;
import dev.friendai.agent.autonomy.ShiftStopWhen;
import dev.friendai.execution.TaskDisposition;
import java.util.List;
import java.util.Locale;

/** Small deterministic rules for whether routine autonomous work needs an owner-facing line. */
public final class CompanionSpeechPolicy {
    public static final long AUTHORITY_QUESTION_COOLDOWN_MILLIS = 5 * 60_000L;

    private CompanionSpeechPolicy() {}

    /** Player messages always earn a reply; outcome-only turns may use tools for learning without narration. */
    public static boolean maySpeak(List<BrainEvent> events, boolean owesPlayerReply) {
        return owesPlayerReply || events.stream().anyMatch(CompanionSpeechPolicy::worthSpeaking);
    }

    /**
     * A successful step inside a plan is intermediate: the plan's own terminal event speaks once, so narrating every
     * step would repeat the same outcome. Direct work, failures, blockers, world notices and player lines keep their
     * voice, and the panel keeps the intermediate progress visible.
     */
    static boolean worthSpeaking(BrainEvent event) {
        if (event instanceof BrainEvent.SkillOutcome) {
            return false;
        }
        return !(event instanceof BrainEvent.TaskFinished task && task.success() && task.planId() > 0);
    }

    /** Cancellation is already kept in task history and does not need a model turn by itself. */
    public static boolean needsModelTurn(BrainEvent event) {
        return switch (event) {
            case BrainEvent.TaskCancelled ignored -> false;
            case BrainEvent.TaskFinished task -> task.disposition() != TaskDisposition.CANCELLED;
            case BrainEvent.PlanFinished plan -> plan.disposition() != TaskDisposition.CANCELLED;
            default -> true;
        };
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
        String blocker = ShiftBlocker.key(summary);
        return history.stream()
                .filter(record -> !record.success() && record.id() != shift.id())
                .noneMatch(record -> ShiftBlocker.key(record.summary()).equals(blocker));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
