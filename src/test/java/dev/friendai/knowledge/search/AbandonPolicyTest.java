package dev.friendai.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AbandonPolicyTest {
    private static AbandonPolicy.Facts facts(float health, int food, boolean night, boolean sheltered,
            boolean online, boolean sameDimension) {
        return new AbandonPolicy.Facts(health, 20, food, night, sheltered, online, sameDimension);
    }

    @Test
    void healthyDaylightSearchContinues() {
        assertTrue(AbandonPolicy.check(facts(20, 20, false, false, true, true)).isEmpty());
        assertTrue(AbandonPolicy.check(facts(20, 20, true, true, true, true)).isEmpty());
    }

    @Test
    void eachSafetyReasonStopsTheSearch() {
        assertEquals(AbandonPolicy.Reason.LOW_HEALTH,
                AbandonPolicy.check(facts(5, 20, false, false, true, true)).orElseThrow());
        assertEquals(AbandonPolicy.Reason.HUNGER,
                AbandonPolicy.check(facts(20, 3, false, false, true, true)).orElseThrow());
        assertEquals(AbandonPolicy.Reason.NIGHT_UNSHELTERED,
                AbandonPolicy.check(facts(20, 20, true, false, true, true)).orElseThrow());
        assertEquals(AbandonPolicy.Reason.OWNER_GONE,
                AbandonPolicy.check(facts(20, 20, false, false, false, true)).orElseThrow());
        assertEquals(AbandonPolicy.Reason.WRONG_DIMENSION,
                AbandonPolicy.check(facts(20, 20, false, false, true, false)).orElseThrow());
    }

    @Test
    void ownerReasonsOutrankHealth() {
        assertEquals(AbandonPolicy.Reason.OWNER_GONE,
                AbandonPolicy.check(facts(1, 1, true, false, false, false)).orElseThrow());
    }
}
