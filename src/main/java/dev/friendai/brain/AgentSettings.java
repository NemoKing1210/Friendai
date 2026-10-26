package dev.friendai.brain;

/**
 * The live agent-layer settings the brain reads on every turn.
 *
 * @param promptBudget character budget of the system prompt; zero or less means unlimited
 */
public record AgentSettings(
        boolean toolRouting,
        Autonomy autonomy,
        int maxReplans,
        int goalTickSec,
        int promptBudget,
        int plannerTimeoutSec) {

    public static final AgentSettings DEFAULTS = new AgentSettings(true, Autonomy.ASSISTIVE, 3, 30, 24_000, 60);

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
