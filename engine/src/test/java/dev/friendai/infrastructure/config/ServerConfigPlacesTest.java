package dev.friendai.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerConfigPlacesTest {
    @Test
    void placesDefaultsComeFromTheCurrentVersion() {
        ServerConfig config = new ServerConfig();
        assertTrue(ServerConfig.VERSION >= 11);
        assertTrue(config.places.enabled);
        assertEquals(16, config.places.autoMineMinBlocks);
        assertEquals(128, config.places.maxPlaces);
    }

    @Test
    void placesValuesAreClamped() {
        ServerConfig config = new ServerConfig();
        config.places.maxPlaces = 1;
        config.places.promptLimit = 99;
        config.places.promptBudget = 5;
        config.places.mergeRadius = 0;
        config.places.autoMineMinBlocks = 0;
        config.places.plannerLimit = 999;
        config.validate();
        assertEquals(8, config.places.maxPlaces);
        assertEquals(12, config.places.promptLimit);
        assertEquals(200, config.places.promptBudget);
        assertEquals(1, config.places.mergeRadius);
        assertEquals(1, config.places.autoMineMinBlocks);
        assertEquals(128, config.places.plannerLimit);
    }
}
