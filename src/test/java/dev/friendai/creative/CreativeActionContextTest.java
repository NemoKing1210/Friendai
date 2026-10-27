package dev.friendai.creative;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreativeActionContextTest {
    @Test
    void contextOnlyMatchesItsPrincipalAndCurrentModeRevision() {
        UUID owner = UUID.randomUUID();
        CreativeActionContext context = new CreativeActionContext(owner, 12L);

        assertTrue(context.matches(owner, 12L));
        assertFalse(context.matches(UUID.randomUUID(), 12L));
        assertFalse(context.matches(owner, 13L));
    }
}
