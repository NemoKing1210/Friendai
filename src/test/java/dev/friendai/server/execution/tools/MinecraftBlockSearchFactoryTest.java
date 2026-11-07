package dev.friendai.server.execution.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The guard the server's block-search composition applies before it creates anything. */
class MinecraftBlockSearchFactoryTest {
    @Test
    void a_guest_cannot_borrow_a_search() {
        assertFalse(
                MinecraftBlockSearchFactory.accepts(caller(AccessLevel.GUEST), "minecraft:stone", 16, 100),
                "a delegated search never runs above the caller's own authority");
    }

    @Test
    void trusted_and_owner_callers_are_accepted() {
        assertTrue(MinecraftBlockSearchFactory.accepts(caller(AccessLevel.TRUSTED), "minecraft:stone", 16, 100));
        assertTrue(MinecraftBlockSearchFactory.accepts(caller(AccessLevel.OWNER), "minecraft:stone", 16, 100));
    }

    @Test
    void an_invalid_call_is_refused() {
        Caller trusted = caller(AccessLevel.TRUSTED);

        assertFalse(MinecraftBlockSearchFactory.accepts(null, "minecraft:stone", 16, 100));
        assertFalse(MinecraftBlockSearchFactory.accepts(trusted, " ", 16, 100));
        assertFalse(MinecraftBlockSearchFactory.accepts(trusted, "minecraft:stone", 0, 100));
        assertFalse(MinecraftBlockSearchFactory.accepts(trusted, "minecraft:stone", 16, 0));
    }

    private static Caller caller(AccessLevel access) {
        return new Caller(UUID.randomUUID(), access.label(), access);
    }
}
