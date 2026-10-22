package dev.friedai.debug;

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
                        DebugAccess.Action.RUN_TOOL),
                operatorOnly);
    }
}
