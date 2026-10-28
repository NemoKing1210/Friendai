package dev.friendai.knowledge.search;

/**
 * Keeps the owner in the loop while a search runs: cap the radius so a nearby owner stays in sight, hold when they
 * lag behind, and ask once — never per leg — when the goal needs to go further than that. Autonomous searches ask
 * nobody; they stay inside the task's own limits and leave one line.
 */
public final class EscortPolicy {
    public record Config(int sightRadius, int holdDistance, int resumeDistance, boolean waitForOwner) {
        public static final Config DEFAULTS = new Config(32, 48, 24, true);

        public Config {
            if (sightRadius < 8 || sightRadius > 128) throw new IllegalArgumentException("sightRadius must be between 8 and 128");
            if (holdDistance <= sightRadius || holdDistance > 512)
                throw new IllegalArgumentException("holdDistance must be above sightRadius and at most 512");
            if (resumeDistance < 1 || resumeDistance >= holdDistance)
                throw new IllegalArgumentException("resumeDistance must be below holdDistance");
        }
    }

    public enum Action { NONE, CAP_RADIUS, WAIT, ASK_ONCE, AUTONOMOUS }

    /**
     * @param ownerWasNear whether the owner was within sight earlier in this search, so WAIT means "lagged"
     * @param holding whether the search is already waiting for the owner; it then resumes only at resumeDistance
     */
    public record Input(boolean ownerOnline, boolean autonomous, boolean ownerInCurrentDimension,
                        double ownerDistance, boolean ownerWasNear, boolean holding, int wantedRadius,
                        boolean askedForGoal) {
        public Input {
            if (ownerDistance < 0 || !Double.isFinite(ownerDistance))
                throw new IllegalArgumentException("ownerDistance must be finite and non-negative");
            if (wantedRadius < 1) throw new IllegalArgumentException("wantedRadius must be positive");
        }
    }

    public record Decision(Action action, int radiusCap, String message) {
        public Decision {
            if (radiusCap < 0) throw new IllegalArgumentException("radiusCap must not be negative");
            message = message == null ? "" : message;
        }
    }

    private final Config config;

    public EscortPolicy(Config config) {
        this.config = java.util.Objects.requireNonNull(config);
    }

    public Decision decide(Input input) {
        if (!input.ownerOnline() || !input.ownerInCurrentDimension()) {
            return new Decision(Action.NONE, input.wantedRadius(), "");
        }
        if (input.autonomous()) {
            return new Decision(Action.AUTONOMOUS, input.wantedRadius(), input.askedForGoal() ? ""
                    : "free time: searching on its own, nobody to ask");
        }
        if (config.waitForOwner() && input.holding() && input.ownerDistance() > config.resumeDistance()) {
            return new Decision(Action.WAIT, 0, "waiting for the owner, "
                    + Math.round(input.ownerDistance()) + " blocks away");
        }
        if (config.waitForOwner() && input.ownerWasNear() && input.ownerDistance() > config.holdDistance()) {
            return new Decision(Action.WAIT, 0, "waiting for the owner, "
                    + Math.round(input.ownerDistance()) + " blocks away");
        }
        if (input.ownerDistance() > config.holdDistance()) {
            return input.wantedRadius() > config.sightRadius() && !input.askedForGoal()
                    ? new Decision(Action.ASK_ONCE, config.sightRadius(), "the owner is "
                            + Math.round(input.ownerDistance()) + " blocks away; ask once whether to search ~"
                            + input.wantedRadius() + " blocks or to wait")
                    : new Decision(Action.NONE, input.wantedRadius(), "");
        }
        if (input.ownerDistance() > config.sightRadius() && input.wantedRadius() > config.sightRadius()
                && !input.askedForGoal()) {
            return new Decision(Action.ASK_ONCE, config.sightRadius(), "the owner is "
                    + Math.round(input.ownerDistance()) + " blocks away; ask once whether to search ~"
                    + input.wantedRadius() + " blocks or to wait");
        }
        int cap = Math.min(input.wantedRadius(), config.sightRadius());
        return new Decision(Action.CAP_RADIUS, cap, "");
    }

    public Config config() { return config; }
}
