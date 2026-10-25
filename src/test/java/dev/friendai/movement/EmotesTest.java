package dev.friendai.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmotesTest {
    @Test
    void parsesEveryLabelAndRejectsUnknown() {
        for (Emotes.Kind kind : Emotes.Kind.values()) {
            assertEquals(kind, Emotes.Kind.parse(kind.label()).orElseThrow());
        }
        assertTrue(Emotes.Kind.parse("dance").isEmpty());
        assertTrue(Emotes.Kind.parse(null).isEmpty());
    }

    @Test
    void everyGestureRestsOutsideItsDuration() {
        for (Emotes.Kind kind : Emotes.Kind.values()) {
            Emotes.Pose after = Emotes.at(kind, kind.duration());
            assertEquals(0.0f, after.yawOffset(), 0.0f, kind.name());
            assertEquals(0.0f, after.pitch(), 0.0f, kind.name());
            assertFalse(after.sneak() || after.swing() || after.jump(), kind.name());
            assertFalse(Emotes.at(kind, -1).swing());
        }
    }

    @Test
    void waveSwingsRepeatedlyWithoutJumping() {
        int swings = 0;
        for (int t = 0; t < Emotes.Kind.WAVE.duration(); t++) {
            Emotes.Pose pose = Emotes.at(Emotes.Kind.WAVE, t);
            assertFalse(pose.jump());
            if (pose.swing()) {
                swings++;
            }
        }
        assertEquals(4, swings);
    }

    @Test
    void bowCrouchesInTheMiddleOnly() {
        assertFalse(Emotes.at(Emotes.Kind.BOW, 0).sneak());
        assertTrue(Emotes.at(Emotes.Kind.BOW, 10).sneak());
        assertFalse(Emotes.at(Emotes.Kind.BOW, 25).sneak());
    }

    @Test
    void spinTurnsAlmostAFullCircle() {
        float last = Emotes.at(Emotes.Kind.SPIN, Emotes.Kind.SPIN.duration() - 1).yawOffset();
        assertTrue(last > 300.0f && last < 360.0f);
    }

    @Test
    void shakeHeadOscillatesAroundZero() {
        boolean left = false;
        boolean right = false;
        for (int t = 0; t < Emotes.Kind.SHAKE_HEAD.duration(); t++) {
            float yaw = Emotes.at(Emotes.Kind.SHAKE_HEAD, t).yawOffset();
            left |= yaw < -10.0f;
            right |= yaw > 10.0f;
        }
        assertTrue(left && right);
    }

    @Test
    void jumpForJoyJumpsTwice() {
        int jumps = 0;
        for (int t = 0; t < Emotes.Kind.JUMP_FOR_JOY.duration(); t++) {
            if (Emotes.at(Emotes.Kind.JUMP_FOR_JOY, t).jump()) {
                jumps++;
            }
        }
        assertEquals(2, jumps);
    }
}
