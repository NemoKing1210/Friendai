package dev.friendai.agent.turn;

import dev.friendai.agent.route.PlannerMode;

/**
 * The live agent-layer settings the brain reads on every turn.
 *
 * @param promptBudget character budget of the system prompt; zero or less means unlimited
 * @param plannerMode how eagerly a plan is drafted before the model answers
 */
public record AgentSettings(
        boolean toolRouting,
        Autonomy autonomy,
        int maxReplans,
        int goalTickSec,
        int promptBudget,
        int plannerTimeoutSec,
        PlannerMode plannerMode) {

    public static final AgentSettings DEFAULTS =
            new AgentSettings(true, Autonomy.ASSISTIVE, 3, 30, 24_000, 20, PlannerMode.AUTO);

    /** How much the companion may do without being asked. */
    public enum Autonomy {
        PASSIVE,
        ASSISTIVE,
        AUTONOMOUS;

        public static Autonomy parse(String text) {
            if (text == null) {
                return ASSISTIVE;
            }
            return switch (text.toLowerCase(java.util.Locale.ROOT)) {
                case "passive" -> PASSIVE;
                case "autonomous" -> AUTONOMOUS;
                default -> ASSISTIVE;
            };
        }
    }
}
