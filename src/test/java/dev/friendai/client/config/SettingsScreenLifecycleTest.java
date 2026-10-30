package dev.friendai.client.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.SettingsSession;
import org.junit.jupiter.api.Test;

class SettingsScreenLifecycleTest {
    @Test
    void dropdownRemovalKeepsDraftAndCallbacksAliveUntilActualExit() {
        try (var session = SettingsSession.open(7, ClientConfig.defaults(), null, false)) {
            var lifecycle = new SettingsScreenLifecycle(session);
            lifecycle.removed(true);
            assertTrue(session.current(7));
            lifecycle.removed(false);
            assertFalse(session.current(7));
        }
    }

    @Test
    void rebuildKeepsSessionOpenButLaterExitClosesIt() {
        try (var session = SettingsSession.open(7, ClientConfig.defaults(), null, false)) {
            var lifecycle = new SettingsScreenLifecycle(session);
            lifecycle.replace(() -> lifecycle.removed(false));
            assertTrue(session.isOpen());
            lifecycle.removed(false);
            assertFalse(session.isOpen());
        }
    }

    @Test
    void failedRebuildDoesNotDisableExitCleanup() {
        try (var session = SettingsSession.open(7, ClientConfig.defaults(), null, false)) {
            var lifecycle = new SettingsScreenLifecycle(session);
            assertThrows(
                    IllegalStateException.class,
                    () -> lifecycle.replace(() -> {
                        throw new IllegalStateException("test failure");
                    }));
            lifecycle.removed(false);
            assertFalse(session.isOpen());
        }
    }
}
