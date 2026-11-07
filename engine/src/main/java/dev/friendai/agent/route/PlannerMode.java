package dev.friendai.agent.route;

import java.util.Locale;

/**
 * How eagerly the brain asks the planner before the model answers. The default, {@link #AUTO}, is the policy of
 * {@link PlanningPolicy}: a complex or explicitly requested order, a live troubled plan, an unmet goal and a free-time
 * shift are planned, and a simple action is not. {@link #OFF} never asks, so the model plans with its own tools.
 * {@link #ALWAYS} asks for every actionable owner order too, which is the diagnosis setting for a shorter timeout.
 */
public enum PlannerMode {
    AUTO,
    OFF,
    ALWAYS;

    /** The configured name as an enum; unknown or missing text reads as {@link #AUTO}. */
    public static PlannerMode parse(String text) {
        if (text == null) return AUTO;
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "off" -> OFF;
            case "always" -> ALWAYS;
            default -> AUTO;
        };
    }
}
