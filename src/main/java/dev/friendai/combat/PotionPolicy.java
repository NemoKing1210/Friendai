package dev.friendai.combat;

/**
 * Splash-potion safety policy. Default deny: a harmful splash near a protected entity, a beneficial splash on an
 * enemy, or effects whose net result is unknown all reject the throw. Lingering potions are out of scope.
 */
public final class PotionPolicy {
    private PotionPolicy() {}

    public static PotionDecision evaluate(PotionFacts facts, CombatSnapshot snapshot, CombatSettings settings) {
        if (!settings.allowSplashPotions()) {
            return PotionDecision.reject(PotionDecision.Reason.DISABLED);
        }
        if (facts.mixedEffects()) {
            return PotionDecision.reject(PotionDecision.Reason.MIXED_EFFECTS);
        }
        if (facts.affectedProtected() > 0) {
            return PotionDecision.reject(PotionDecision.Reason.PROTECTED_ENTITY);
        }
        if (facts.beneficial() && !facts.harmful()) {
            // A beneficial splash on the current enemy helps them. Support only for protected allies, which the
            // adapter reports as affectedProtected > 0 and therefore rejects above.
            return PotionDecision.reject(PotionDecision.Reason.BENEFICIAL_ON_ENEMY);
        }
        if (!facts.harmful()) {
            return PotionDecision.reject(PotionDecision.Reason.NO_TARGET);
        }
        if (facts.affectedEnemies() <= 0 || !snapshot.target().present() || !snapshot.target().attackable()) {
            return PotionDecision.reject(PotionDecision.Reason.NO_TARGET);
        }
        if (facts.shortestDurationTicks() < PotionFacts.MIN_USEFUL_DURATION) {
            return PotionDecision.reject(PotionDecision.Reason.USELESS_DURATION);
        }
        if (!facts.cooldownReady()) {
            return PotionDecision.reject(PotionDecision.Reason.COOLDOWN);
        }
        if (facts.budgetLeft() <= 0) {
            return PotionDecision.reject(PotionDecision.Reason.BUDGET);
        }
        if (!facts.impactSafe()) {
            return PotionDecision.reject(PotionDecision.Reason.UNSAFE_IMPACT);
        }
        return PotionDecision.allow();
    }
}
