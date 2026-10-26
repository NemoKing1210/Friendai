package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DebugAccessTest {
    @Test
    void operatorOnlyActionsAreExactlyTheDebugOnes() {
        Set<DebugAccess.Action> operatorOnly = EnumSet.noneOf(DebugAccess.Action.class);
        for (DebugAccess.Action a : DebugAccess.Action.values()) {
            assertTrue(DebugAccess.allowed(a, true), a + " must be allowed for operators");
            if (!DebugAccess.allowed(a, false)) {
                operatorOnly.add(a);
            }
        }
        assertEquals(
                EnumSet.of(
                        DebugAccess.Action.VIEW_LOG,
                        DebugAccess.Action.VIEW_PROMPT,
                        DebugAccess.Action.SEND_MESSAGE,
                        DebugAccess.Action.RUN_TOOL,
                        DebugAccess.Action.VIEW_MOVEMENT),
                operatorOnly);
    }

    @Test
    void memoryActionsAreAllowedForEveryone() {
        assertTrue(DebugAccess.allowed(DebugAccess.Action.VIEW_MEMORY, false));
        assertTrue(DebugAccess.allowed(DebugAccess.Action.EDIT_MEMORY, false));
    }

    @Test
    void renameIsAllowedForEveryone() {
        assertTrue(DebugAccess.allowed(DebugAccess.Action.RENAME, false));
    }
}
