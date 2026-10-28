package dev.friendai.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BodyFacingTest {

    private static final float EPS = 1.0e-3f;

    @Test
    void turnsTheBodyAtMostThirtyDegreesPerTick() {
        BodyFacing facing = new BodyFacing();
        facing.aim(90.0f, 0.0f, 5);
        assertTrue(facing.step(0.0f, 0.0f, 0.0f), "the first step must write");
        assertEquals(30.0f, facing.bodyYaw(), EPS);
        facing.step(30.0f, 0.0f, 30.0f);
        assertEquals(60.0f, facing.bodyYaw(), EPS);
    }

    @Test
    void turnsTheShortWayAcrossTheHalfCircle() {
        BodyFacing facing = new BodyFacing();
        facing.aim(-170.0f, 0.0f, 2);
        facing.step(170.0f, 0.0f, 170.0f);
        assertEquals(-170.0f, facing.bodyYaw(), EPS, "a 20-degree turn across the wrap is not a 340-degree one");
    }

    @Test
    void leadsWithTheHeadAtSeventyFiveDegreesPerTick() {
        BodyFacing facing = new BodyFacing();
        facing.aim(90.0f, 0.0f, 3);
        facing.step(0.0f, 0.0f, 0.0f);
        assertEquals(30.0f, facing.bodyYaw(), EPS);
        assertEquals(75.0f, facing.headYaw(), EPS);
    }

    @Test
    void writesNothingInsideTheHysteresisBand() {
        BodyFacing facing = new BodyFacing();
        facing.aim(2.0f, 2.0f, 3);
        assertFalse(facing.step(0.0f, 0.0f, 0.0f), "within three degrees nothing may be written");
        assertEquals(0.0f, facing.bodyYaw(), EPS);
        assertEquals(0.0f, facing.headYaw(), EPS);
        assertEquals(0.0f, facing.pitch(), EPS);
    }

    @Test
    void anActionOutranksTravelAndTravelResumesAfterItExpires() {
        BodyFacing facing = new BodyFacing();
        facing.aim(90.0f, 0.0f, 2);
        facing.travelYaw(0.0f);
        assertEquals(BodyFacing.Source.ACTION, facing.source());
        assertTrue(facing.step(0.0f, 0.0f, 0.0f));
        assertEquals(30.0f, facing.bodyYaw(), EPS);
        facing.step(30.0f, 0.0f, 30.0f);
        assertFalse(facing.hasClaim(), "the action hold expired");
        facing.travelYaw(0.0f);
        assertEquals(BodyFacing.Source.TRAVEL, facing.source());
        facing.step(30.0f, 0.0f, 30.0f);
        assertEquals(0.0f, facing.bodyYaw(), EPS);
    }

    @Test
    void verticalTravelKeepsTheYawAndOnlyPitches() {
        BodyFacing facing = new BodyFacing();
        facing.travelPitch(-45.0f);
        assertTrue(facing.step(90.0f, 0.0f, 90.0f));
        assertFalse(facing.bodyChanged(), "a pure climb must not turn the body");
        assertEquals(90.0f, facing.bodyYaw(), EPS);
        facing.travelPitch(-45.0f);
        facing.step(90.0f, 0.0f, 90.0f);
        assertEquals(-45.0f, facing.pitch(), EPS);
        assertEquals(90.0f, facing.bodyYaw(), EPS);
    }

    @Test
    void accumulatesThePitchAcrossTheVanillaReset() {
        // LookControl resets xRot to 0 at the start of every tick, so the policy must carry the held pitch itself.
        BodyFacing facing = new BodyFacing();
        facing.aim(0.0f, 200.0f, 10);
        facing.step(0.0f, 0.0f, 0.0f);
        assertEquals(30.0f, facing.pitch(), EPS);
        facing.aim(0.0f, 200.0f, 10);
        facing.step(0.0f, 0.0f, 0.0f);
        assertEquals(60.0f, facing.pitch(), EPS);
        facing.aim(0.0f, 200.0f, 10);
        facing.step(0.0f, 0.0f, 0.0f);
        assertEquals(90.0f, facing.pitch(), EPS, "the target pitch is clamped to the straight-down limit");
    }

    @Test
    void keepsTheHeadInsideTheBodyCone() {
        BodyFacing facing = new BodyFacing();
        for (int tick = 0; tick < 4; tick++) {
            facing.aim(-170.0f, 0.0f, 5);
            facing.step(facing.bodyYaw(), 0.0f, facing.headYaw());
            assertTrue(
                    Facing.difference(facing.headYaw(), facing.bodyYaw()) <= 75.0f + EPS,
                    "head " + facing.headYaw() + " left the body cone " + facing.bodyYaw());
        }
    }

    @Test
    void writesNothingWithoutAClaim() {
        BodyFacing facing = new BodyFacing();
        assertFalse(facing.step(45.0f, 10.0f, 40.0f));
        assertFalse(facing.bodyChanged());
        assertFalse(facing.headChanged());
        assertFalse(facing.pitchChanged());
        assertEquals(BodyFacing.Source.NONE, facing.source());
    }

    @Test
    void clearDropsTheClaim() {
        BodyFacing facing = new BodyFacing();
        facing.aim(90.0f, 0.0f, 5);
        facing.clear();
        assertFalse(facing.hasClaim());
        assertFalse(facing.step(0.0f, 0.0f, 0.0f));
        assertEquals(BodyFacing.Source.NONE, facing.source());
    }
}
