package dev.friendai.knowledge.search;

import java.util.Optional;

/** When a search must stop instead of pressing on. Each reason reaches the owner as a sentence, not as "failed". */
public final class AbandonPolicy {
    public enum Reason {
        OWNER_GONE("the owner is no longer available"),
        WRONG_DIMENSION("the owner is in another dimension"),
        LOW_HEALTH("health is too low to keep searching"),
        HUNGER("hunger is too low to keep searching"),
        NIGHT_UNSHELTERED("it is night and there is no shelter");

        private final String text;

        Reason(String text) { this.text = text; }

        public String text() { return text; }
    }

    public record Facts(float health, float maxHealth, int food, boolean night, boolean sheltered,
                        boolean ownerOnline, boolean ownerInCurrentDimension) {
        public Facts {
            if (!Float.isFinite(health) || health < 0 || !Float.isFinite(maxHealth) || maxHealth <= 0)
                throw new IllegalArgumentException("invalid health");
            if (food < 0) throw new IllegalArgumentException("food must not be negative");
        }
    }

    private AbandonPolicy() {}

    /** The first reason to stop, or empty when the search may continue. Order is deliberate: safety before mood. */
    public static Optional<Reason> check(Facts facts) {
        if (!facts.ownerOnline()) return Optional.of(Reason.OWNER_GONE);
        if (!facts.ownerInCurrentDimension()) return Optional.of(Reason.WRONG_DIMENSION);
        if (facts.maxHealth() >= 10 && facts.health() < 6) return Optional.of(Reason.LOW_HEALTH);
        if (facts.food() < 4) return Optional.of(Reason.HUNGER);
        if (facts.night() && !facts.sheltered()) return Optional.of(Reason.NIGHT_UNSHELTERED);
        return Optional.empty();
    }
}
