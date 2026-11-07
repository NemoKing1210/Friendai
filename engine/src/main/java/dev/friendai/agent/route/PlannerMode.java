package dev.friendai.agent.route;

import java.util.List;
import java.util.Locale;

/**
 * How eagerly the brain asks the planner before the model answers. The default, {@link #AUTO}, is the policy of
 * {@link PlanningPolicy}: a complex or explicitly requested order, a live troubled plan, an unmet goal and a free-time
 * shift are planned, and a simple action is not. {@link #OFF} never asks, so every subject — an owner order, an unmet
 * goal, a troubled plan and a free-time shift — goes to the model or to the deterministic fallback instead. {@link
 * #ALWAYS} also asks for every actionable owner order, which is the diagnosis setting.
 */
public enum PlannerMode {
    AUTO,
    OFF,
    ALWAYS;

    /** The canonical configured spellings, in {@link #values()} order; the config, the screen and the parser share it. */
    public static final List<String> CONFIG_VALUES = List.of("auto", "off", "always");

    /** The configured name as an enum; unknown or missing text reads as {@link #AUTO}. */
    public static PlannerMode parse(String text) {
        if (text == null) return AUTO;
        int index = CONFIG_VALUES.indexOf(text.strip().toLowerCase(Locale.ROOT));
        return index < 0 ? AUTO : values()[index];
    }
}
