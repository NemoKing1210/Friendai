package dev.friendai.presentation.companion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MenuOpenCoalescerTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void oneOpenPerOwnerKindAndTick() {
        MenuOpenCoalescer coalescer = new MenuOpenCoalescer();
        assertTrue(coalescer.mayBuild("menu", OWNER, 100));
        assertFalse(coalescer.mayBuild("menu", OWNER, 100), "a repeated open in the same tick rebuilds nothing");
        assertTrue(coalescer.mayBuild("menu", OWNER, 101), "a new tick builds again");
        assertTrue(coalescer.mayBuild("memory", OWNER, 101), "a different view is its own snapshot");
        assertTrue(coalescer.mayBuild("menu", OTHER, 101), "another owner has its own budget");
    }

    @Test
    void aReconnectStartsFresh() {
        MenuOpenCoalescer coalescer = new MenuOpenCoalescer();
        assertTrue(coalescer.mayBuild("menu", OWNER, 100));
        coalescer.forget(OWNER);
        assertTrue(coalescer.mayBuild("menu", OWNER, 100), "a reconnecting owner may open again in the same tick");
    }
}
