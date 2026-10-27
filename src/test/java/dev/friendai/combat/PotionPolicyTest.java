package dev.friendai.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PotionPolicyTest {
    private static final CombatSettings ENABLED = new CombatSettings(
            ProtectionPolicy.BODYGUARD, FightingStyle.BALANCED, true, 24, RetreatPreference.STANDARD);

    private static CombatSnapshot enemySnapshot() {
        return new CombatSnapshot(
                CombatSnapshot.Self.bare(0.8),
                new CombatSnapshot.Target(true, true, true, true, false, true, 6.0, 6.0, false, false),
                CombatSnapshot.Options.none());
    }

    private static PotionFacts harmful(int protectedEntities, int enemies) {
        return new PotionFacts(true, false, false, 100, protectedEntities, enemies, true, true, 2);
    }

    @Test
    void harmfulEffectRejectedForProtectedEntity() {
        PotionDecision decision = PotionPolicy.evaluate(harmful(1, 1), enemySnapshot(), ENABLED);
        assertFalse(decision.allowed());
        assertEquals(PotionDecision.Reason.PROTECTED_ENTITY, decision.reason());
    }

    @Test
    void beneficialEffectRejectedForEnemy() {
        PotionFacts facts = new PotionFacts(false, true, false, 100, 0, 1, true, true, 2);
        PotionDecision decision = PotionPolicy.evaluate(facts, enemySnapshot(), ENABLED);
        assertFalse(decision.allowed());
        assertEquals(PotionDecision.Reason.BENEFICIAL_ON_ENEMY, decision.reason());
    }

    @Test
    void mixedEffectsRejectedWhenNetSafetyUnknown() {
        PotionFacts facts = new PotionFacts(true, true, true, 100, 0, 1, true, true, 2);
        PotionDecision decision = PotionPolicy.evaluate(facts, enemySnapshot(), ENABLED);
        assertFalse(decision.allowed());
        assertEquals(PotionDecision.Reason.MIXED_EFFECTS, decision.reason());
    }

    @Test
    void potionAllowanceRequired() {
        CombatSettings disabled = new CombatSettings(
                ProtectionPolicy.BODYGUARD, FightingStyle.BALANCED, false, 24, RetreatPreference.STANDARD);
        PotionDecision decision = PotionPolicy.evaluate(harmful(0, 1), enemySnapshot(), disabled);
        assertFalse(decision.allowed());
        assertEquals(PotionDecision.Reason.DISABLED, decision.reason());
    }

    @Test
    void minimumUsefulEffectRequired() {
        PotionFacts facts = new PotionFacts(true, false, false, 5, 0, 1, true, true, 2);
        PotionDecision decision = PotionPolicy.evaluate(facts, enemySnapshot(), ENABLED);
        assertFalse(decision.allowed());
        assertEquals(PotionDecision.Reason.USELESS_DURATION, decision.reason());
    }

    @Test
    void consumptionBudgetAndCooldownAreRespected() {
        PotionFacts cooling = new PotionFacts(true, false, false, 100, 0, 1, true, false, 2);
        assertEquals(PotionDecision.Reason.COOLDOWN,
                PotionPolicy.evaluate(cooling, enemySnapshot(), ENABLED).reason());

        PotionFacts spent = new PotionFacts(true, false, false, 100, 0, 1, true, true, 0);
        assertEquals(PotionDecision.Reason.BUDGET,
                PotionPolicy.evaluate(spent, enemySnapshot(), ENABLED).reason());

        PotionFacts unsafe = new PotionFacts(true, false, false, 100, 0, 1, false, true, 2);
        assertEquals(PotionDecision.Reason.UNSAFE_IMPACT,
                PotionPolicy.evaluate(unsafe, enemySnapshot(), ENABLED).reason());

        assertTrue(PotionPolicy.evaluate(harmful(0, 1), enemySnapshot(), ENABLED).allowed());
    }

    @Test
    void noEnemyMeansNoThrow() {
        PotionDecision decision = PotionPolicy.evaluate(harmful(0, 0), enemySnapshot(), ENABLED);
        assertFalse(decision.allowed());
        assertEquals(PotionDecision.Reason.NO_TARGET, decision.reason());
    }
}
