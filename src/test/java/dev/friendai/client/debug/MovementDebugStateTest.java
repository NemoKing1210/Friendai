package dev.friendai.client.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.debug.MovementDebug;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MovementDebugStateTest {
    private static MovementDebug validView() {
        return new MovementDebug(4, 0, List.of(), List.of(), List.of(), List.of(), List.of("task: mining"));
    }

    @BeforeEach
    void enableState() {
        MovementDebugState.reset();
        MovementDebugState.setEnabled(true);
    }

    @AfterEach
    void resetState() {
        MovementDebugState.reset();
    }

    @Test
    void emptyUpdateClearsTheLastView() {
        MovementDebugState.onData(validView().encode());
        MovementDebugState.onData(new byte[0]);

        assertEquals(java.util.Optional.empty(), MovementDebugState.view());
    }

    @Test
    void malformedUpdateKeepsTheLastValidView() {
        MovementDebug valid = validView();
        MovementDebugState.onData(valid.encode());

        assertThrows(IllegalArgumentException.class, () -> MovementDebugState.onData(new byte[] {1, 2, 3}));
        assertEquals(java.util.Optional.of(valid), MovementDebugState.view());
    }

    @Test
    void disconnectResetClearsTheViewAndDisablesTheOverlay() {
        MovementDebugState.onData(validView().encode());
        MovementDebugState.reset();

        assertFalse(MovementDebugState.enabled());
        assertEquals(java.util.Optional.empty(), MovementDebugState.view());
    }

    @Test
    void overlayStartsOffAndResetSwitchesItOff() {
        MovementDebugState.reset();
        assertFalse(MovementDebugState.enabled());
        MovementDebugState.setEnabled(true);
        assertTrue(MovementDebugState.enabled());
        MovementDebugState.reset();
        assertFalse(MovementDebugState.enabled());
    }
}
