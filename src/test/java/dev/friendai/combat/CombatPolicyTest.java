package dev.friendai.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CombatPolicyTest {

    private static CombatSnapshot snapshot(
            CombatSnapshot.Self self, CombatSnapshot.Target target, CombatSnapshot.Options options) {
        return new CombatSnapshot(self, target, options);
    }

    private static CombatSnapshot.Self calm() {
        return CombatSnapshot.Self.bare(0.8);
    }

    private static CombatSnapshot.Self with(
            CombatSnapshot.Self base,
            boolean danger,
            boolean canEat,
            boolean retreatPossible,
            boolean shield,
            boolean frontal,
            boolean projectile,
            boolean dodge,
            boolean cover,
            boolean bow,
            boolean lane,
            boolean ally) {
        return new CombatSnapshot.Self(
                base.healthFraction(),
                danger,
                canEat,
                retreatPossible,
                shield,
                false,
                frontal,
                projectile,
                dodge,
                cover,
                bow,
                lane,
                ally,
                false,
                false,
                false,
                0,
                false);
    }

    private static CombatSnapshot.Self windingUp(CombatSnapshot.Self base) {
        return new CombatSnapshot.Self(
                base.healthFraction(),
                false,
                false,
                false,
                true,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                0,
                true);
    }

    private static CombatSnapshot.Target target(
            boolean aggressedSelf, boolean aggressedOwner, boolean hostile, double distance, double anchorDistance) {
        return new CombatSnapshot.Target(
                true, true, true, aggressedSelf, aggressedOwner, hostile, distance, anchorDistance, false, false);
    }

    private static CombatSettings settings(ProtectionPolicy protection, FightingStyle style) {
        return new CombatSettings(protection, style, false, 24, RetreatPreference.STANDARD);
    }

    @Test
    void selfOnlyIgnoresOwnerOnlyAggressor() {
        CombatDecision decision = CombatPolicy.decide(
                snapshot(calm(), target(false, true, false, 4.0, 4.0), new CombatSnapshot.Options(true, 8.0, true)),
                settings(ProtectionPolicy.SELF_ONLY, FightingStyle.BALANCED));
        assertEquals(CombatDecision.Action.NONE, decision.action());
        assertEquals(CombatDecision.Reason.NONE, decision.reason());
    }

    @Test
    void selfOnlyDefendsItself() {
        CombatDecision decision = CombatPolicy.decide(
                snapshot(calm(), target(true, false, false, 4.0, 4.0), new CombatSnapshot.Options(true, 8.0, true)),
                settings(ProtectionPolicy.SELF_ONLY, FightingStyle.BALANCED));
        assertEquals(CombatDecision.Action.ATTACK, decision.action());
        assertEquals(CombatDecision.Reason.DEFEND_SELF, decision.reason());
    }

    @Test
    void bodyguardDefendsOwner() {
        CombatDecision decision = CombatPolicy.decide(
                snapshot(calm(), target(false, true, false, 4.0, 4.0), new CombatSnapshot.Options(true, 8.0, true)),
                settings(ProtectionPolicy.BODYGUARD, FightingStyle.BALANCED));
        assertEquals(CombatDecision.Action.ATTACK, decision.action());
        assertEquals(CombatDecision.Reason.DEFEND_OWNER, decision.reason());
    }

    @Test
    void avoidCombatNeverChoosesAttack() {
        CombatSnapshot aggressive =
                snapshot(calm(), target(true, true, true, 2.0, 2.0), new CombatSnapshot.Options(true, 9.0, true));
        for (CombatSnapshot snapshot : new CombatSnapshot[] {
            aggressive,
            snapshot(
                    with(calm(), false, false, true, false, false, false, false, false, true, true, false),
                    target(true, true, true, 6.0, 6.0),
                    new CombatSnapshot.Options(true, 9.0, true))
        }) {
            CombatDecision decision =
                    CombatPolicy.decide(snapshot, settings(ProtectionPolicy.AVOID_COMBAT, FightingStyle.BALANCED));
            assertFalse(decision.attacks(), "avoid combat chose " + decision);
        }
        assertEquals(
                CombatDecision.Action.AVOID,
                CombatPolicy.decide(
                                snapshot(calm(), CombatSnapshot.Target.none(), CombatSnapshot.Options.none()),
                                settings(ProtectionPolicy.AVOID_COMBAT, FightingStyle.BALANCED))
                        .action());
    }

    @Test
    void physicalProjectileThreatStillAllowsEvasionInSelfOnly() {
        CombatSnapshot.Self self =
                with(calm(), false, false, false, true, true, true, false, false, false, false, false);
        CombatDecision decision = CombatPolicy.decide(
                snapshot(self, target(false, true, true, 10.0, 3.0), CombatSnapshot.Options.none()),
                settings(ProtectionPolicy.SELF_ONLY, FightingStyle.BALANCED));
        assertEquals(CombatDecision.Action.SHIELD, decision.action());
        assertEquals(CombatDecision.Reason.BLOCK_PROJECTILE, decision.reason());
    }

    @Test
    void projectileFromBehindIsNotAFrontalBlock() {
        CombatSnapshot.Self self =
                with(calm(), false, false, false, true, false, true, true, false, false, false, false);
        CombatDecision decision = CombatPolicy.decide(
                snapshot(self, target(true, false, true, 4.0, 4.0), new CombatSnapshot.Options(true, 8.0, true)),
                settings(ProtectionPolicy.BODYGUARD, FightingStyle.BALANCED));
        assertEquals(CombatDecision.Action.DODGE, decision.action());
        assertEquals(CombatDecision.Reason.EVADE_PROJECTILE, decision.reason());
    }

    @Test
    void emergencyDefensePrecedesAttack() {
        CombatSnapshot.Self self =
                with(calm(), true, false, true, false, false, false, false, false, false, false, false);
        CombatDecision decision = CombatPolicy.decide(
                snapshot(self, target(true, false, true, 2.0, 2.0), new CombatSnapshot.Options(true, 20.0, true)),
                settings(ProtectionPolicy.BODYGUARD, FightingStyle.BALANCED));
        assertEquals(CombatDecision.Action.RETREAT, decision.action());
        assertEquals(CombatDecision.Reason.IMMINENT_DANGER, decision.reason());
        assertFalse(decision.attacks());
    }

    @Test
    void conservativeRetreatIncreasesDisengagePriority() {
        CombatSnapshot.Self self = new CombatSnapshot.Self(
                0.45, false, false, true, false, false, false, false, false, false, true, true, false, false, false,
                false, 0, false);
        CombatSnapshot snapshot =
                snapshot(self, target(true, false, true, 4.0, 4.0), new CombatSnapshot.Options(true, 8.0, true));
        CombatDecision standard = CombatPolicy.decide(
                snapshot,
                new CombatSettings(
                        ProtectionPolicy.BODYGUARD, FightingStyle.BALANCED, false, 24, RetreatPreference.STANDARD));
        CombatDecision conservative = CombatPolicy.decide(
                snapshot,
                new CombatSettings(
                        ProtectionPolicy.BODYGUARD, FightingStyle.BALANCED, false, 24, RetreatPreference.CONSERVATIVE));
        assertEquals(CombatDecision.Action.ATTACK, standard.action());
        assertEquals(CombatDecision.Action.RETREAT, conservative.action());
        assertEquals(CombatDecision.Reason.SURVIVE, conservative.reason());
    }

    @Test
    void pursuitStopsAtConfiguredAnchorLimit() {
        CombatDecision decision = CombatPolicy.decide(
                snapshot(calm(), target(true, false, true, 26.0, 26.0), new CombatSnapshot.Options(true, 8.0, true)),
                new CombatSettings(
                        ProtectionPolicy.BODYGUARD, FightingStyle.BALANCED, false, 24, RetreatPreference.STANDARD));
        assertEquals(CombatDecision.Action.DISENGAGE, decision.action());
        assertEquals(CombatDecision.Reason.OUT_OF_PURSUIT, decision.reason());
    }

    @Test
    void rangedBandRetreatsFromClosingMelee() {
        CombatSnapshot.Self self =
                with(calm(), false, false, false, false, false, false, false, false, true, true, false);
        CombatSnapshot.Target closing =
                new CombatSnapshot.Target(true, true, true, true, false, true, 2.5, 2.5, false, true);
        CombatDecision decision = CombatPolicy.decide(
                snapshot(self, closing, new CombatSnapshot.Options(false, 0.0, false)),
                settings(ProtectionPolicy.BODYGUARD, FightingStyle.RANGED));
        assertEquals(CombatDecision.Action.KEEP_RANGE, decision.action());
        assertEquals(CombatDecision.Reason.STYLE_RANGE, decision.reason());
    }

    @Test
    void blockedFiringLaneRejectsRelease() {
        CombatSnapshot.Self self =
                with(calm(), false, false, false, false, false, false, false, false, true, false, false);
        CombatSnapshot snapshot = snapshot(self, target(true, false, true, 8.0, 8.0), CombatSnapshot.Options.none());
        assertFalse(CombatPolicy.releaseAllowed(snapshot));
        CombatDecision decision =
                CombatPolicy.decide(snapshot, settings(ProtectionPolicy.BODYGUARD, FightingStyle.RANGED));
        assertNotEquals(CombatDecision.Action.FIRE_BOW, decision.action());
    }

    @Test
    void allyInLaneRejectsRelease() {
        CombatSnapshot.Self self =
                with(calm(), false, false, false, false, false, false, false, false, true, true, true);
        assertFalse(CombatPolicy.releaseAllowed(
                snapshot(self, target(true, false, true, 8.0, 8.0), CombatSnapshot.Options.none())));
        assertEquals(
                CombatDecision.Action.ATTACK,
                CombatPolicy.decide(
                                snapshot(
                                        self,
                                        target(true, false, true, 8.0, 8.0),
                                        new CombatSnapshot.Options(true, 8.0, true)),
                                settings(ProtectionPolicy.BODYGUARD, FightingStyle.BALANCED))
                        .action());
    }

    @Test
    void leadEstimateIsBoundedForMovingTargets() {
        CombatPolicy.Lead near = CombatPolicy.leadPoint(10, 64, 0, 0.5, 0, 0, 0, 64, 0, 3.0, 20);
        assertEquals(4, near.ticks(), "distance 10 at speed 3 is 4 ticks");
        assertEquals(10.0 + 0.5 * 4, near.x(), 1e-9);

        CombatPolicy.Lead far = CombatPolicy.leadPoint(1000, 64, 0, 10.0, 0, 0, 0, 64, 0, 1.0, 20);
        assertEquals(20, far.ticks());
        assertTrue(far.x() <= 1000 + 1.0 * 20 + 1e-9, "lead exceeded the bounded horizon: " + far.x());

        CombatPolicy.Lead still = CombatPolicy.leadPoint(5, 64, 5, 0, 0, 0, 0, 64, 0, 3.0, 20);
        assertEquals(5.0, still.x(), 1e-9);
        assertEquals(5.0, still.z(), 1e-9);
    }

    @Test
    void simultaneousProjectileAndMeleeChooseOneAction() {
        CombatSnapshot.Self self =
                with(calm(), false, false, false, true, true, true, false, false, false, false, false);
        CombatDecision decision = CombatPolicy.decide(
                snapshot(self, target(true, false, true, 2.0, 2.0), new CombatSnapshot.Options(true, 9.0, true)),
                settings(ProtectionPolicy.BODYGUARD, FightingStyle.BALANCED));
        assertEquals(CombatDecision.Action.SHIELD, decision.action());
    }

    @Test
    void activeReasonMatchesSelectedAction() {
        for (CombatDecision decision : new CombatDecision[] {
            CombatPolicy.decide(
                    snapshot(calm(), target(true, false, true, 2.0, 2.0), new CombatSnapshot.Options(true, 8.0, true)),
                    settings(ProtectionPolicy.BODYGUARD, FightingStyle.BALANCED)),
            CombatPolicy.decide(
                    snapshot(
                            CombatSnapshot.Self.bare(0.1),
                            target(true, false, true, 2.0, 2.0),
                            new CombatSnapshot.Options(true, 8.0, true)),
                    settings(ProtectionPolicy.BODYGUARD, FightingStyle.BALANCED)),
            CombatDecision.none()
        }) {
            if (decision.action() == CombatDecision.Action.NONE) {
                assertEquals(CombatDecision.Reason.NONE, decision.reason());
            } else {
                assertNotEquals(CombatDecision.Reason.NONE, decision.reason());
            }
        }
    }

    @Test
    void threatPriorityAndRetargetPenaltyAreStable() {
        assertTrue(CombatPolicy.ThreatKind.IMMINENT_EXPLOSIVE.priority()
                > CombatPolicy.ThreatKind.ABOUT_TO_HIT_PROTECTED.priority());
        assertTrue(CombatPolicy.ThreatKind.ABOUT_TO_HIT_PROTECTED.priority()
                > CombatPolicy.ThreatKind.RANGED_CLEAR_LANE.priority());
        assertTrue(CombatPolicy.ThreatKind.RANGED_CLEAR_LANE.priority() > CombatPolicy.ThreatKind.ROUTINE.priority());

        double current = CombatPolicy.targetScore(CombatPolicy.ThreatKind.ROUTINE, 5.0, true, 5.0, 24);
        double other = CombatPolicy.targetScore(CombatPolicy.ThreatKind.ROUTINE, 5.0, false, 5.0, 24);
        assertTrue(current > other, "an unnecessary retarget must be penalised");
        double explosive = CombatPolicy.targetScore(CombatPolicy.ThreatKind.IMMINENT_EXPLOSIVE, 10.0, true, 10.0, 24);
        assertTrue(explosive > current, "imminent explosives outrank routine targets");
        double outside = CombatPolicy.targetScore(CombatPolicy.ThreatKind.IMMINENT_EXPLOSIVE, 1.0, true, 30.0, 24);
        assertTrue(outside < explosive, "a target beyond the pursuit bound is penalised");
        double routineOutside = CombatPolicy.targetScore(CombatPolicy.ThreatKind.ROUTINE, 1.0, true, 30.0, 24);
        assertTrue(routineOutside < other, "excessive pursuit is penalised");
    }

    @Test
    void windupRaisesTheShieldBeforeTheArrowExists() {
        CombatDecision decision = CombatPolicy.decide(
                snapshot(
                        windingUp(calm()),
                        target(true, false, true, 10.0, 10.0),
                        new CombatSnapshot.Options(false, 0.0, false)),
                settings(ProtectionPolicy.BODYGUARD, FightingStyle.BALANCED));
        assertEquals(CombatDecision.Action.SHIELD, decision.action());
        assertEquals(CombatDecision.Reason.BLOCK_PROJECTILE, decision.reason());
    }

    @Test
    void windupInReachStillSwings() {
        CombatDecision decision = CombatPolicy.decide(
                snapshot(
                        windingUp(calm()),
                        target(true, false, true, 2.0, 2.0),
                        new CombatSnapshot.Options(true, 8.0, true)),
                settings(ProtectionPolicy.BODYGUARD, FightingStyle.BALANCED));
        assertEquals(CombatDecision.Action.ATTACK, decision.action());
    }

    @Test
    void noticeGateEmitsOncePerCondition() {
        CombatPolicy.NoticeGate gate = new CombatPolicy.NoticeGate();
        assertTrue(gate.shouldEmit("retreating"));
        assertFalse(gate.shouldEmit("retreating"));
        assertTrue(gate.shouldEmit("out of arrows"));
        assertTrue(gate.shouldEmit("retreating"));
        gate.clear();
        assertTrue(gate.shouldEmit("retreating"));
    }
}
