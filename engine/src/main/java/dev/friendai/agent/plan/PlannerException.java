package dev.friendai.agent.plan;

/** The planner could not produce a usable plan. Detail is for redacted operator diagnostics only. */
public final class PlannerException extends RuntimeException {
    private final PlanFailure failure;

    public PlannerException(PlanFailure failure) {
        super(failure.code() + ": " + failure.detail());
        this.failure = failure;
    }

    public PlanFailure failure() {
        return failure;
    }
}
