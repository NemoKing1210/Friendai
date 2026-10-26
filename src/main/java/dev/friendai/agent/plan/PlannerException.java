package dev.friendai.agent.plan;

/** The planner could not produce a usable plan. The message never carries secrets. */
public final class PlannerException extends RuntimeException {
    public PlannerException(String message) {
        super(message);
    }
}
