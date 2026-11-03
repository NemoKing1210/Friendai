package dev.friendai.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.features.storage.StorageLimits;
import org.junit.jupiter.api.Test;

class ServerConfigStorageTest {
    @Test
    void storageDefaultsComeFromTheCurrentVersion() {
        var config = new ServerConfig();
        assertEquals(22, ServerConfig.VERSION);
        assertEquals(StorageLimits.DEFAULTS, config.storage.toLimits());
    }

    @Test
    void storageValuesAreClamped() {
        var config = new ServerConfig();
        config.storage.maxContainers = 1;
        config.storage.maxKindsPerContainer = 999;
        config.storage.staleAfterTicks = 1;
        config.validate();
        assertEquals(16, config.storage.maxContainers);
        assertEquals(256, config.storage.maxKindsPerContainer);
        assertEquals(600, config.storage.staleAfterTicks);
    }
}
