package dev.friendai.features.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpatialAudioTest {
    private static final double EPS = 1e-6;

    @Test
    void centerAheadIsBalancedAndAttenuated() {
        SpatialAudio.Mix mix = SpatialAudio.mix(0, 0, 0, 0, 0, 0, 10, 16);
        assertEquals(0.375 * Math.sqrt(0.5), mix.left(), EPS);
        assertEquals(0.375 * Math.sqrt(0.5), mix.right(), EPS);
        assertEquals(0.375, mix.level(), EPS);
        assertEquals(10, mix.distance(), EPS);
    }

    @Test
    void sourceToTheRightFavoursTheRightChannel() {
        // Facing south (yaw 0), the right hand points west (-X).
        SpatialAudio.Mix mix = SpatialAudio.mix(0, 0, 0, 0, -10, 0, 0, 16);
        assertEquals(0.0, mix.left(), EPS);
        assertEquals(0.375, mix.right(), EPS);
    }

    @Test
    void sourceToTheLeftFavoursTheLeftChannel() {
        SpatialAudio.Mix mix = SpatialAudio.mix(0, 0, 0, 0, 10, 0, 0, 16);
        assertEquals(0.375, mix.left(), EPS);
        assertEquals(0.0, mix.right(), EPS);
    }

    @Test
    void behindIsCentered() {
        SpatialAudio.Mix mix = SpatialAudio.mix(0, 0, 0, 0, 0, 0, -10, 16);
        assertEquals(0.375 * Math.sqrt(0.5), mix.left(), EPS);
        assertEquals(0.375 * Math.sqrt(0.5), mix.right(), EPS);
    }

    @Test
    void directlyAboveIsCentered() {
        SpatialAudio.Mix mix = SpatialAudio.mix(0, 0, 0, 0, 0, 10, 0, 16);
        assertEquals(0.375 * Math.sqrt(0.5), mix.left(), EPS);
        assertEquals(0.375 * Math.sqrt(0.5), mix.right(), EPS);
    }

    @Test
    void yawTurnsThePan() {
        // Facing west (yaw 90) puts the same western source ahead: centred.
        SpatialAudio.Mix mix = SpatialAudio.mix(0, 0, 0, 90, -10, 0, 0, 16);
        assertEquals(0.375 * Math.sqrt(0.5), mix.left(), EPS);
        assertEquals(0.375 * Math.sqrt(0.5), mix.right(), EPS);
    }

    @Test
    void atOrBeyondRangeIsSilent() {
        SpatialAudio.Mix at = SpatialAudio.mix(0, 0, 0, 0, 0, 0, 16, 16);
        assertEquals(0.0, at.left(), EPS);
        assertEquals(0.0, at.right(), EPS);
        assertEquals(0.0, at.level(), EPS);
        SpatialAudio.Mix beyond = SpatialAudio.mix(0, 0, 0, 0, 0, 0, 40, 16);
        assertEquals(0.0, beyond.left(), EPS);
        assertEquals(0.0, beyond.right(), EPS);
    }

    @Test
    void zeroRangeMeansFullVolume() {
        SpatialAudio.Mix mix = SpatialAudio.mix(0, 0, 0, 0, 0, 0, 10, 0);
        assertEquals(Math.sqrt(0.5), mix.left(), EPS);
        assertEquals(Math.sqrt(0.5), mix.right(), EPS);
        assertEquals(1.0, mix.level(), EPS);
    }

    @Test
    void sourceAtTheListenerIsCenteredAtFullLevel() {
        SpatialAudio.Mix mix = SpatialAudio.mix(0, 0, 0, 0, 0, 0, 0, 16);
        assertEquals(0.0, mix.distance(), EPS);
        assertEquals(1.0, mix.level(), EPS);
        assertEquals(Math.sqrt(0.5), mix.left(), EPS);
        assertEquals(Math.sqrt(0.5), mix.right(), EPS);
    }

    @Test
    void leftAndRightNeverExceedOne() {
        SpatialAudio.Mix mix = SpatialAudio.mix(0, 0, 0, 0, -1, 0, 0, 16);
        assertTrue(mix.left() >= 0 && mix.left() <= 1);
        assertTrue(mix.right() >= 0 && mix.right() <= 1);
    }
}
