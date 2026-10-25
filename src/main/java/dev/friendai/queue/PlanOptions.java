package dev.friendai.queue;

public record PlanOptions(int priority, FailurePolicy onFailure, long timeLimitTicks) {
    public static final PlanOptions DEFAULT = new PlanOptions(0, FailurePolicy.ASK, 0);
    public PlanOptions { onFailure = onFailure == null ? FailurePolicy.ASK : onFailure; timeLimitTicks = Math.max(0, timeLimitTicks); }
}
