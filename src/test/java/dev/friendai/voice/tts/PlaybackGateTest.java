package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlaybackGateTest {
    @Test
    void aStopInvalidatesAPlaybackThatTookItsTokenBeforeIt() {
        PlaybackGate gate = new PlaybackGate();
        long token = gate.token();
        gate.cancel();
        assertFalse(gate.stillOpen(token));
    }

    @Test
    void aPlaybackThatTakesItsTokenAfterAStopStaysOpen() {
        PlaybackGate gate = new PlaybackGate();
        gate.cancel();
        long token = gate.token();
        assertTrue(gate.stillOpen(token));
    }

    @Test
    void oneStopInvalidatesEveryTokenFromBeforeIt() {
        PlaybackGate gate = new PlaybackGate();
        long first = gate.token();
        long second = gate.token();
        gate.cancel();
        assertFalse(gate.stillOpen(first));
        assertFalse(gate.stillOpen(second));
        assertTrue(gate.stillOpen(gate.token()));
    }
}
