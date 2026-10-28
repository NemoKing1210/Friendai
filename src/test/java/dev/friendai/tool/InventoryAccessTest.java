package dev.friendai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class InventoryAccessTest {
    @Test
    void allowsTheOwnerCloseBy() {
        assertEquals(InventoryAccess.Denial.NONE, InventoryAccess.check(true, true, true, 2.0));
        assertEquals(
                InventoryAccess.Denial.NONE, InventoryAccess.check(true, true, true, InventoryAccess.MAX_DISTANCE));
    }

    @Test
    void rejectsBeyondTheReach() {
        assertEquals(
                InventoryAccess.Denial.TOO_FAR,
                InventoryAccess.check(true, true, true, InventoryAccess.MAX_DISTANCE + 0.01));
    }

    @Test
    void rejectsAnotherDimension() {
        assertEquals(InventoryAccess.Denial.OTHER_DIMENSION, InventoryAccess.check(true, false, true, 1.0));
    }

    @Test
    void rejectsNonOwners() {
        assertEquals(InventoryAccess.Denial.NOT_OWNER, InventoryAccess.check(false, true, true, 1.0));
    }

    @Test
    void goneWinsOverEveryOtherCheck() {
        assertEquals(InventoryAccess.Denial.GONE, InventoryAccess.check(false, false, false, 99.0));
        assertEquals(InventoryAccess.Denial.GONE, InventoryAccess.check(true, true, false, 0.0));
    }
}
