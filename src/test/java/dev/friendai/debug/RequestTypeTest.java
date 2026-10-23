package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class RequestTypeTest {
    @Test
    void unknownOrdinalsBecomeInvalid() {
        assertEquals(RequestType.INVALID, RequestType.fromOrdinal(-1));
        assertEquals(RequestType.INVALID, RequestType.fromOrdinal(RequestType.values().length));
        assertEquals(RequestType.SUMMON, RequestType.fromOrdinal(RequestType.SUMMON.ordinal()));
    }

    @Test
    void everyTypeMapsToExpectedAction() {
        assertEquals(Optional.of(DebugAccess.Action.VIEW_STATUS), RequestType.SUBSCRIBE.action());
        assertEquals(Optional.of(DebugAccess.Action.VIEW_STATUS), RequestType.UNSUBSCRIBE.action());
        assertEquals(Optional.of(DebugAccess.Action.SUMMON), RequestType.SUMMON.action());
        assertEquals(Optional.of(DebugAccess.Action.DISMISS), RequestType.DISMISS.action());
        assertEquals(Optional.of(DebugAccess.Action.STOP), RequestType.STOP.action());
        assertEquals(Optional.of(DebugAccess.Action.VIEW_PROMPT), RequestType.REQUEST_PROMPT.action());
        assertEquals(Optional.of(DebugAccess.Action.SEND_MESSAGE), RequestType.SEND_MESSAGE.action());
        assertEquals(Optional.of(DebugAccess.Action.RUN_TOOL), RequestType.RUN_TOOL.action());
        assertTrue(RequestType.INVALID.action().isEmpty());
    }
}
