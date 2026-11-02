package dev.friendai.execution.queue;

/** Creation options for one plan; {@code autoRepair} is the owner's consent to insert prerequisites. */
public record PlanOptions(int priority, FailurePolicy onFailure, long timeLimitTicks, boolean autoRepair) {
    public static final PlanOptions DEFAULT = new PlanOptions(0, FailurePolicy.ASK, 0, true);

    /** Legacy shape: repair consent defaults to on. */
    public PlanOptions(int priority, FailurePolicy onFailure, long timeLimitTicks) {
        this(priority, onFailure, timeLimitTicks, true);
    }

    public PlanOptions {
        onFailure = onFailure == null ? FailurePolicy.ASK : onFailure;
        timeLimitTicks = Math.max(0, timeLimitTicks);
    }
}
