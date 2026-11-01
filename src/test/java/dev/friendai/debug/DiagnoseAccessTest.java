package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiagnoseAccessTest {
    @Test
    void theOwnerAndAnOperatorMayCollect() {
        UUID owner = UUID.randomUUID();
        assertTrue(DiagnoseAccess.allowed(owner, owner, false), "owner");
        assertTrue(DiagnoseAccess.allowed(UUID.randomUUID(), owner, true), "operator");
        assertFalse(DiagnoseAccess.allowed(UUID.randomUUID(), owner, false), "guest");
    }
}
