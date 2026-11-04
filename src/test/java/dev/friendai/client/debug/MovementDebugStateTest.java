package dev.friendai.client.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.debug.MovementDebug;
import dev.friendai.protocol.debug.MovementDebugView;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MovementDebugStateTest {
    private static MovementDebug validView() {
        return new MovementDebug(4, 0, List.of(), List.of(), List.of(), List.of(), List.of("task: mining"));
    }

    private static MovementDebugView viewWithId(int companionId) {
        return new MovementDebugView(
                companionId,
                -1,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    @BeforeEach
    void enableState() {
        MovementDebugState.reset();
        MovementDebugState.setEnabled(true);
    }

    @AfterEach
    void resetState() {
        MovementDebugState.useClockForTests(null);
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

    @Test
    void keepsTheLastViewAcrossAThreeSecondServerStall() {
        MovementDebugState.useClockForTests(() -> 0L);
        MovementDebugState.onView(viewWithId(7));

        MovementDebugState.useClockForTests(() -> 3_000L);
        assertTrue(MovementDebugState.view().isPresent());

        MovementDebugState.useClockForTests(() -> 5_001L);
        assertTrue(MovementDebugState.view().isEmpty());
    }

    @Test
    void reportsWaitingUntilTheFirstViewArrives() {
        assertTrue(MovementDebugState.awaitingView());

        MovementDebugState.onView(viewWithId(7));
        assertFalse(MovementDebugState.awaitingView());

        MovementDebugState.onView(viewWithId(0));
        assertTrue(MovementDebugState.awaitingView());
    }

    @Test
    void aRejectedToggleIsVisibleToTheScreen() {
        MovementDebugState.setRequestFailed(true);
        assertTrue(MovementDebugState.requestFailed());

        MovementDebugState.setRequestFailed(false);
        assertFalse(MovementDebugState.requestFailed());
        MovementDebugState.setRequestFailed(true);
        MovementDebugState.reset();
        assertFalse(MovementDebugState.requestFailed());
    }
}
