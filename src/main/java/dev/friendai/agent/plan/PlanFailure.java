package dev.friendai.agent.plan;

import dev.friendai.text.UserMessage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** A bounded diagnostic for one planner attempt; raw provider errors never belong here. */
public record PlanFailure(Code code, String detail, int attempt) {
    public enum Code {
        EMPTY_REPLY,
        NO_JSON,
        BROKEN_JSON,
        TRUNCATED,
        STEPS_INVALID,
        TOO_LARGE,
        REQUEST_FAILED
    }

    public PlanFailure {
        detail = detail == null ? "" : detail;
        attempt = Math.max(1, attempt);
    }

    public PlanFailure atAttempt(int number) {
        return new PlanFailure(code, detail, number);
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
            case TOO_LARGE -> UserMessage.of("friendai.freetime.no_plan.too_large");
            case REQUEST_FAILED -> UserMessage.of("friendai.freetime.no_plan.unavailable");
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
