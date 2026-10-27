package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RequestTypeCompatibilityTest {
    @Test
    void creativeRequestIsAppendedWithoutChangingExistingOrdinals() {
        assertEquals(20, RequestType.SET_AUTO_RESPAWN.ordinal());
        assertEquals(24, RequestType.PLACES_FORGET.ordinal());
        assertEquals(25, RequestType.INVALID.ordinal());
        assertEquals(26, RequestType.SET_CREATIVE_MODE.ordinal());
        assertEquals(RequestType.SET_CREATIVE_MODE, RequestType.fromOrdinal(26));
    }
}
