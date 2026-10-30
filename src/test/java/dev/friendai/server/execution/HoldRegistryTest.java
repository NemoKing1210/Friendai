package dev.friendai.server.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HoldRegistryTest {
    @Test
    void independentHoldsRequireTheirOwnReleaseAndDuplicateCloseIsHarmless() {
        HoldRegistry registry = new HoldRegistry();
        ExecutionHold selfCare = registry.acquire(HoldReason.SELF_CARE);
        ExecutionHold inventory = registry.acquire(HoldReason.INVENTORY);

        selfCare.close();
        selfCare.close();

        assertTrue(registry.held());
        assertEquals(1, registry.activeCount());
        inventory.close();
        assertFalse(registry.held());
    }

    @Test
    void releaseAllClosesOutstandingDisconnectHoldWithoutCancellingWork() {
        HoldRegistry registry = new HoldRegistry();
        ExecutionHold disconnectHold = registry.acquire(HoldReason.SELF_CARE);

        registry.closeAll();

        assertFalse(registry.held());
        assertEquals(0, registry.activeCount());
        disconnectHold.close();
        assertFalse(registry.held());
    }
}
