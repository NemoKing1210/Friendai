package dev.friendai.features.speech.wake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WakeWordPolicyTest {
    private static final int COOLDOWN = 40;
    private static final int MAX_UTTERANCE = 600;
    private static final int SILENCE = 20;

    private static WakeWordPolicy enabled() {
        return new WakeWordPolicy(new WakeWordPolicy.Config(true, COOLDOWN, MAX_UTTERANCE, SILENCE));
    }

    private static WakeWordPolicy armed() {
        WakeWordPolicy policy = enabled();
        assertEquals(WakeWordPolicy.State.ARMED, policy.arm());
        return policy;
    }

    private static WakeWordPolicy capturing() {
        WakeWordPolicy policy = armed();
        assertEquals(WakeWordPolicy.State.CAPTURING, policy.detectorHit());
        return policy;
    }

    private static void finishBySilence(WakeWordPolicy policy) {
        policy.levelBelowThreshold();
        for (int i = 0; i < SILENCE; i++) {
            policy.tick();
        }
        assertEquals(WakeWordPolicy.State.COOLDOWN, policy.state());
    }

    @Test
    void disabledPolicyNeverArms() {
        WakeWordPolicy policy = new WakeWordPolicy(new WakeWordPolicy.Config(false, COOLDOWN, MAX_UTTERANCE, SILENCE));

        assertEquals(WakeWordPolicy.State.IDLE, policy.arm());
        for (WakeWordPolicy.Event event : WakeWordPolicy.Event.values()) {
            assertEquals(WakeWordPolicy.State.IDLE, policy.feed(event));
        }
        assertEquals(WakeWordPolicy.State.IDLE, policy.state());
    }

    @Test
    void detectorHitWithoutArmingIsIgnored() {
        WakeWordPolicy policy = enabled();

        assertEquals(WakeWordPolicy.State.IDLE, policy.detectorHit());
        assertEquals(WakeWordPolicy.State.IDLE, policy.tick());
        assertFalse(policy.capturing());
    }

    @Test
    void loudnessNeverStartsCaptureWithoutAHit() {
        WakeWordPolicy policy = armed();

        assertEquals(WakeWordPolicy.State.ARMED, policy.levelAboveThreshold());
        assertEquals(WakeWordPolicy.State.ARMED, policy.levelBelowThreshold());
        assertEquals(WakeWordPolicy.State.ARMED, policy.tick());
    }

    @Test
    void armedHitStartsCapture() {
        WakeWordPolicy policy = capturing();

        assertTrue(policy.capturing());
        assertEquals(0, policy.captureTicks());
        assertEquals(0, policy.silentTicks());
    }

    @Test
    void silenceEndsCapture() {
        WakeWordPolicy policy = capturing();
        policy.levelBelowThreshold();

        for (int i = 0; i < SILENCE - 1; i++) {
            assertEquals(WakeWordPolicy.State.CAPTURING, policy.tick());
        }
        assertEquals(SILENCE - 1, policy.silentTicks());

        assertEquals(WakeWordPolicy.State.COOLDOWN, policy.tick());
        assertEquals(0, policy.captureTicks());
        assertEquals(0, policy.silentTicks());
        assertEquals(COOLDOWN, policy.cooldownRemaining());
    }

    @Test
    void soundResetsTheSilenceWindow() {
        WakeWordPolicy policy = capturing();
        policy.levelBelowThreshold();
        for (int i = 0; i < SILENCE - 1; i++) {
            policy.tick();
        }

        policy.levelAboveThreshold();
        assertEquals(0, policy.silentTicks());

        policy.levelBelowThreshold();
        for (int i = 0; i < SILENCE - 1; i++) {
            assertEquals(WakeWordPolicy.State.CAPTURING, policy.tick());
        }
        assertEquals(WakeWordPolicy.State.COOLDOWN, policy.tick());
    }

    @Test
    void maxUtteranceBoundEndsCapture() {
        WakeWordPolicy policy = capturing();

        for (int i = 0; i < MAX_UTTERANCE - 1; i++) {
            policy.levelAboveThreshold();
            assertEquals(WakeWordPolicy.State.CAPTURING, policy.tick());
        }

        policy.levelAboveThreshold();
        assertEquals(WakeWordPolicy.State.COOLDOWN, policy.tick());
        assertEquals(0, policy.captureTicks());
    }

    @Test
    void cooldownBlocksImmediateRearm() {
        WakeWordPolicy policy = capturing();
        finishBySilence(policy);

        assertEquals(WakeWordPolicy.State.COOLDOWN, policy.detectorHit());
        assertEquals(WakeWordPolicy.State.COOLDOWN, policy.arm());

        for (int i = 0; i < COOLDOWN - 1; i++) {
            assertEquals(WakeWordPolicy.State.COOLDOWN, policy.tick());
        }
        assertEquals(1, policy.cooldownRemaining());
        assertEquals(WakeWordPolicy.State.ARMED, policy.tick());
        assertEquals(0, policy.cooldownRemaining());
        assertEquals(WakeWordPolicy.State.CAPTURING, policy.detectorHit());
    }

    @Test
    void zeroCooldownRearmsAtOnce() {
        WakeWordPolicy policy = new WakeWordPolicy(new WakeWordPolicy.Config(true, 0, MAX_UTTERANCE, SILENCE));
        policy.arm();
        policy.detectorHit();

        policy.levelBelowThreshold();
        for (int i = 0; i < SILENCE; i++) {
            policy.tick();
        }
        assertEquals(WakeWordPolicy.State.ARMED, policy.state());
        assertEquals(0, policy.cooldownRemaining());
    }

    @Test
    void disarmResetsImmediately() {
        WakeWordPolicy policy = capturing();
        for (int i = 0; i < 3; i++) {
            policy.levelAboveThreshold();
            policy.tick();
        }

        assertEquals(WakeWordPolicy.State.IDLE, policy.disarm());
        assertEquals(0, policy.captureTicks());
        assertEquals(0, policy.silentTicks());
        assertEquals(WakeWordPolicy.State.IDLE, policy.detectorHit());
        assertEquals(WakeWordPolicy.State.IDLE, policy.tick());

        assertEquals(WakeWordPolicy.State.ARMED, policy.arm());
        assertEquals(WakeWordPolicy.State.CAPTURING, policy.detectorHit());
    }

    @Test
    void disarmDuringCooldownClearsIt() {
        WakeWordPolicy policy = capturing();
        finishBySilence(policy);

        assertEquals(WakeWordPolicy.State.IDLE, policy.disarm());
        assertEquals(0, policy.cooldownRemaining());
        assertEquals(WakeWordPolicy.State.ARMED, policy.arm());
    }

    @Test
    void feedDispatchesEveryEvent() {
        WakeWordPolicy policy = armed();

        assertEquals(WakeWordPolicy.State.ARMED, policy.feed(WakeWordPolicy.Event.LEVEL_ABOVE_THRESHOLD));
        assertEquals(WakeWordPolicy.State.ARMED, policy.feed(WakeWordPolicy.Event.LEVEL_BELOW_THRESHOLD));
        assertEquals(WakeWordPolicy.State.ARMED, policy.feed(WakeWordPolicy.Event.TICK));
        assertEquals(WakeWordPolicy.State.CAPTURING, policy.feed(WakeWordPolicy.Event.DETECTOR_HIT));
        assertThrows(NullPointerException.class, () -> policy.feed(null));
    }

    @Test
    void feedFromIdleIgnoresTheHit() {
        WakeWordPolicy policy = enabled();

        assertEquals(WakeWordPolicy.State.IDLE, policy.feed(WakeWordPolicy.Event.DETECTOR_HIT));
    }

    @Test
    void configRejectsOutOfRangeDurations() {
        assertThrows(IllegalArgumentException.class, () -> new WakeWordPolicy.Config(true, -1, MAX_UTTERANCE, SILENCE));
        assertThrows(
                IllegalArgumentException.class, () -> new WakeWordPolicy.Config(true, 1_201, MAX_UTTERANCE, SILENCE));
        assertThrows(IllegalArgumentException.class, () -> new WakeWordPolicy.Config(true, COOLDOWN, 0, SILENCE));
        assertThrows(IllegalArgumentException.class, () -> new WakeWordPolicy.Config(true, COOLDOWN, 7_201, SILENCE));
        assertThrows(IllegalArgumentException.class, () -> new WakeWordPolicy.Config(true, COOLDOWN, MAX_UTTERANCE, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WakeWordPolicy.Config(true, COOLDOWN, MAX_UTTERANCE, MAX_UTTERANCE + 1));
        assertEquals(new WakeWordPolicy.Config(false, 40, 600, 20), WakeWordPolicy.Config.DEFAULTS);
    }
}
