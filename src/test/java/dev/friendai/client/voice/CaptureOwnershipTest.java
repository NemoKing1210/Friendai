package dev.friendai.client.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CaptureOwnershipTest {
    @Test
    void anOlderCaptureCannotStopTheNewerOne() {
        CaptureOwnership ownership = new CaptureOwnership();
        long first = ownership.begin();
        assertTrue(ownership.owns(first));
        long second = ownership.begin();
        assertFalse(ownership.owns(first), "the first capture was superseded");
        ownership.end(first);
        assertEquals(second, ownership.current(), "a stale stop must not clear the newer capture");
        ownership.end(second);
        assertEquals(0, ownership.current());
        assertFalse(ownership.owns(0), "no token never owns the line");
    }
}
