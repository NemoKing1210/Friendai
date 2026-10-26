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
    void existingOrdinalsAreStable() {
        assertEquals(0, RequestType.SUBSCRIBE.ordinal());
        assertEquals(1, RequestType.UNSUBSCRIBE.ordinal());
        assertEquals(2, RequestType.SUMMON.ordinal());
        assertEquals(3, RequestType.DISMISS.ordinal());
        assertEquals(4, RequestType.STOP.ordinal());
        assertEquals(5, RequestType.REQUEST_PROMPT.ordinal());
        assertEquals(6, RequestType.SEND_MESSAGE.ordinal());
        assertEquals(7, RequestType.RUN_TOOL.ordinal());
        assertEquals(15, RequestType.TELEPORT.ordinal());
        assertEquals(16, RequestType.MOVE_DEBUG_ON.ordinal());
        assertEquals(17, RequestType.MOVE_DEBUG_OFF.ordinal());
        assertEquals(18, RequestType.RENAME.ordinal());
        assertEquals(19, RequestType.INVALID.ordinal());
        assertEquals(RequestType.values().length - 1, RequestType.INVALID.ordinal());
    }

    @Test
    void renameMapsToItsOwnAction() {
        assertEquals(Optional.of(DebugAccess.Action.RENAME), RequestType.RENAME.action());
    }

    @Test
    void newMemoryTypesMapToTheirActions() {
        assertEquals(Optional.of(DebugAccess.Action.VIEW_MEMORY), RequestType.MEMORY_OPEN.action());
        assertEquals(Optional.of(DebugAccess.Action.VIEW_MEMORY), RequestType.MEMORY_CLOSE.action());
        assertEquals(Optional.of(DebugAccess.Action.EDIT_MEMORY), RequestType.MEMORY_ADD.action());
        assertEquals(Optional.of(DebugAccess.Action.EDIT_MEMORY), RequestType.MEMORY_EDIT.action());
        assertEquals(Optional.of(DebugAccess.Action.EDIT_MEMORY), RequestType.MEMORY_FORGET.action());
        assertEquals(Optional.of(DebugAccess.Action.EDIT_MEMORY), RequestType.MEMORY_CLEAR.action());
        assertEquals(Optional.of(DebugAccess.Action.EDIT_MEMORY), RequestType.MEMORY_TRUST.action());
    }

    @Test
    void everyTypeMapsToExpectedAction() {
        assertEquals(Optional.of(DebugAccess.Action.VIEW_STATUS), RequestType.SUBSCRIBE.action());
        assertEquals(Optional.of(DebugAccess.Action.VIEW_STATUS), RequestType.UNSUBSCRIBE.action());
        assertEquals(Optional.of(DebugAccess.Action.SUMMON), RequestType.SUMMON.action());
        assertEquals(Optional.of(DebugAccess.Action.TELEPORT), RequestType.TELEPORT.action());
        assertEquals(Optional.of(DebugAccess.Action.DISMISS), RequestType.DISMISS.action());
        assertEquals(Optional.of(DebugAccess.Action.STOP), RequestType.STOP.action());
        assertEquals(Optional.of(DebugAccess.Action.VIEW_PROMPT), RequestType.REQUEST_PROMPT.action());
        assertEquals(Optional.of(DebugAccess.Action.SEND_MESSAGE), RequestType.SEND_MESSAGE.action());
        assertEquals(Optional.of(DebugAccess.Action.RUN_TOOL), RequestType.RUN_TOOL.action());
        assertTrue(RequestType.INVALID.action().isEmpty());
    }

    @Test
    void movementDebugRequestsNeedTheOperatorAction() {
        assertEquals(Optional.of(DebugAccess.Action.VIEW_MOVEMENT), RequestType.MOVE_DEBUG_ON.action());
        assertEquals(Optional.of(DebugAccess.Action.VIEW_MOVEMENT), RequestType.MOVE_DEBUG_OFF.action());
    }
}
