package dev.friendai.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServerConfigRuntimeSettingsTest {
    @Test
    void freeTimeWatchdogHasBoundedDefault() {
        ServerConfig config = ServerConfig.defaults();
        assertEquals(120, config.tasks.freeTimeWatchdogSec);

        config.tasks.freeTimeWatchdogSec = 0;
        config.validate();
        assertEquals(1, config.tasks.freeTimeWatchdogSec);
        assertEquals(17, ServerConfig.VERSION);
    }

    @Test
    void buildPaceDefaultsToAPlayersRightClickDelayAndIsClamped() {
        ServerConfig config = ServerConfig.defaults();
        assertEquals(4, config.tasks.placeIntervalTicks);

        config.tasks.placeIntervalTicks = 0;
        config.validate();
        assertEquals(
                1, config.tasks.placeIntervalTicks, "below one tick the printer would place more than once a tick");

        config.tasks.placeIntervalTicks = 999;
        config.validate();
        assertEquals(40, config.tasks.placeIntervalTicks);
    }
}
