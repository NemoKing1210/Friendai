package dev.friendai.agent.plan;

import dev.friendai.foundation.text.UserMessage;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * A bounded diagnostic for one planner attempt; raw provider errors never belong here.
 *
 * @param facts measured values a caller can act on (which queue limit was hit, its observed and allowed value), or
 *     empty when the failure has none
 */
public record PlanFailure(Code code, String detail, int attempt, Map<String, String> facts) {
    public enum Code {
        EMPTY_REPLY,
        NO_JSON,
        BROKEN_JSON,
        TRUNCATED,
        STEPS_INVALID,
        /** The plan parsed, but it breaks a queue limit: the step count, one step's arguments or the total size. */
        LIMIT_EXCEEDED,
        TOO_LARGE,
        REQUEST_FAILED,
        /** The planner's own per-call deadline passed; the HTTP call was abandoned without an answer. */
        TIMEOUT,
        /** The turn that asked for the plan was stopped, so no further attempt belongs to anyone. */
        CANCELLED
    }

    public PlanFailure {
        detail = detail == null ? "" : detail;
        attempt = Math.max(1, attempt);
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }

    /** A failure without measured values. */
    public PlanFailure(Code code, String detail, int attempt) {
        this(code, detail, attempt, Map.of());
    }

    public PlanFailure atAttempt(int number) {
        return new PlanFailure(code, detail, number, facts);
    }

    public static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }
    /** Details may contain model-controlled English; only translated guidance reaches the owner. */
    public static UserMessage ownerMessage(Throwable error) {
        Throwable cause = unwrap(error);
        Code code = cause instanceof PlannerException e ? e.failure().code() : Code.REQUEST_FAILED;
        return switch (code) {
            case EMPTY_REPLY -> UserMessage.of("friendai.freetime.no_plan.empty_reply");
            case NO_JSON -> UserMessage.of("friendai.freetime.no_plan.no_json");
            case BROKEN_JSON -> UserMessage.of("friendai.freetime.no_plan.broken_json");
            case TRUNCATED -> UserMessage.of("friendai.freetime.no_plan.truncated");
            case STEPS_INVALID -> UserMessage.of("friendai.freetime.no_plan.rejected", rejectedStep(cause));
            // Both mean the plan as sent is larger than the queue accepts; the fact values stay in the record.
            case LIMIT_EXCEEDED, TOO_LARGE -> UserMessage.of("friendai.freetime.no_plan.too_large");
            case TIMEOUT -> UserMessage.of("friendai.freetime.no_plan.timeout");
            // A cancelled turn has nobody left to tell: the stop that cancelled it already answered the owner.
            case REQUEST_FAILED, CANCELLED -> UserMessage.of("friendai.freetime.no_plan.unavailable");
        };
    }

    private static UserMessage rejectedStep(Throwable cause) {
        String detail = cause instanceof PlannerException e ? e.failure().detail() : "";
        var match = java.util.regex.Pattern.compile("\\bstep (\\d{1,4})\\b").matcher(detail);
        return match.find()
                ? UserMessage.of("friendai.freetime.no_plan.step", Integer.parseInt(match.group(1)))
                : UserMessage.of("friendai.freetime.no_plan.invalid_steps");
    }
}
