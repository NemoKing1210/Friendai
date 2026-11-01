package dev.friendai.features.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThreatRulesTest {
    private static ThreatRules.Decision decide(
            String id,
            boolean enemy,
            boolean targeting,
            boolean hit,
            boolean daylight,
            boolean ownerOrder,
            boolean group) {
        return ThreatRules.decide(id, enemy, targeting, hit, daylight, ownerOrder, group);
    }

    @Test
    void aZombieIsHostileAndVillagersAreIgnored() {
        ThreatRules.Decision zombie = decide("minecraft:zombie", true, false, false, true, false, false);
        assertEquals(ThreatClass.HOSTILE, zombie.type());
        assertTrue(zombie.attackable());
        assertTrue(zombie.danger());

        ThreatRules.Decision villager = decide("minecraft:villager", false, false, false, true, false, false);
        assertEquals(ThreatClass.IGNORE, villager.type());
        assertFalse(villager.attackable());
        assertFalse(villager.danger());
    }

    @Test
    void theWardenAndDragonAreOnlyDangers() {
        for (String id : ThreatRules.AVOID) {
            ThreatRules.Decision decision = decide(id, true, true, true, true, true, false);
            assertEquals(ThreatClass.AVOID, decision.type(), id);
            assertFalse(decision.attackable(), id);
            assertTrue(decision.danger(), id);
        }
    }

    @Test
    void neutralMobsAreAttackedOnlyWhenProvoked() {
        ThreatRules.Decision calm = decide("minecraft:enderman", true, false, false, true, false, false);
        assertEquals(ThreatClass.PROVOKED_ONLY, calm.type());
        assertFalse(calm.attackable());

        ThreatRules.Decision provoked = decide("minecraft:enderman", true, true, false, true, false, false);
        assertEquals(ThreatClass.HOSTILE, provoked.type());
        assertTrue(provoked.attackable());
    }

    @Test
    void aSpiderIsNeutralInDaylightAndHostileAtNight() {
        ThreatRules.Decision day = decide("minecraft:spider", true, false, false, true, false, false);
        assertEquals(ThreatClass.PROVOKED_ONLY, day.type());
        assertFalse(day.attackable());

        ThreatRules.Decision night = decide("minecraft:spider", true, false, false, false, false, false);
        assertEquals(ThreatClass.HOSTILE, night.type());
        assertTrue(night.attackable());
    }

    @Test
    void aPiglinGroupIsAvoidedUnlessOrdered() {
        ThreatRules.Decision group = decide("minecraft:piglin", true, true, true, true, false, true);
        assertEquals(ThreatClass.AVOID, group.type());
        assertFalse(group.attackable());
        assertTrue(group.danger());

        ThreatRules.Decision ordered = decide("minecraft:piglin", true, true, true, true, true, true);
        assertEquals(ThreatClass.HOSTILE, ordered.type());
        assertTrue(ordered.attackable());
    }

    @Test
    void aZombifiedPiglinThatHitUsIsProvoked() {
        ThreatRules.Decision hit = decide("minecraft:zombified_piglin", true, false, true, true, false, false);
        assertEquals(ThreatClass.HOSTILE, hit.type());
        assertTrue(hit.attackable());
    }
}
