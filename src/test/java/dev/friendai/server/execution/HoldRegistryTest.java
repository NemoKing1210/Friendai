package dev.friendai.server.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HoldRegistryTest {
    @Test
    void independentHoldsRequireTheirOwnReleaseAndDuplicateCloseIsHarmless() {
        HoldRegistry registry = new HoldRegistry();
        ExecutionHold menu = registry.acquire(HoldReason.MENU);
        ExecutionHold inventory = registry.acquire(HoldReason.INVENTORY);

        menu.close();
        menu.close();

        assertTrue(registry.held());
        assertEquals(1, registry.activeCount());
        inventory.close();
        assertFalse(registry.held());
    }

    @Test
    void releaseAllClosesOutstandingDisconnectHoldWithoutCancellingWork() {
        HoldRegistry registry = new HoldRegistry();
        ExecutionHold disconnectHold = registry.acquire(HoldReason.MENU);

        registry.closeAll();

        assertFalse(registry.held());
        assertEquals(0, registry.activeCount());
        disconnectHold.close();
        assertFalse(registry.held());
    }
}
