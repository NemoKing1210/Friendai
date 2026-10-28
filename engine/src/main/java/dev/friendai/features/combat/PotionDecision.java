package dev.friendai.features.combat;

/** Whether a splash potion may be thrown, with a stable rejection reason. */
public record PotionDecision(boolean allowed, Reason reason) {
    public enum Reason {
        ALLOWED,
        DISABLED,
        MIXED_EFFECTS,
        PROTECTED_ENTITY,
        BENEFICIAL_ON_ENEMY,
        NO_TARGET,
        USELESS_DURATION,
        COOLDOWN,
        BUDGET,
        UNSAFE_IMPACT
    }

    public static PotionDecision allow() {
        return new PotionDecision(true, Reason.ALLOWED);
    }

    public static PotionDecision reject(Reason reason) {
        return new PotionDecision(false, reason);
    }
}
