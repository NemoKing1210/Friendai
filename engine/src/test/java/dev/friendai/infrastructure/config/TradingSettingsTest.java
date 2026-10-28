package dev.friendai.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TradingSettingsTest {
    @Test
    void defaultsMatchBoundedLocalRefreshAndWaitPolicy() {
        var config = ServerConfig.defaults();
        var trading = config.tasks.trading;

        assertTrue(trading.enabled);
        assertTrue(trading.idleRefresh);
        assertEquals(64, trading.observationRadius);
        assertEquals(1_200, trading.quoteFreshTicks);
        assertEquals(12_000, trading.locationFreshTicks);
        assertEquals(200, trading.idleRefreshIntervalTicks);
        assertEquals(2_400, trading.waitTicks);
        assertEquals(12_000, trading.maxWaitTicks);
        assertEquals(64, trading.maxBarterIngots);
        assertEquals(8, trading.barterDefaultIngots);
        assertEquals(ServerConfig.VERSION, config.version);
    }

    @Test
    void clampsTradingSettingsAndKeepsWaitBelowMaximum() {
        var config = ServerConfig.defaults();
        var trading = config.tasks.trading;
        trading.observationRadius = 100;
        trading.quoteFreshTicks = 1;
        trading.locationFreshTicks = 99_999;
        trading.idleRefreshIntervalTicks = 1;
        trading.maxWaitTicks = 30;
        trading.waitTicks = 500;
        trading.maxBarterIngots = 0;
        trading.barterDefaultIngots = 64;

        config.validate();

        assertEquals(64, trading.observationRadius);
        assertEquals(20, trading.quoteFreshTicks);
        assertEquals(72_000, trading.locationFreshTicks);
        assertEquals(200, trading.idleRefreshIntervalTicks);
        assertEquals(30, trading.maxWaitTicks);
        assertEquals(30, trading.waitTicks);
        assertEquals(1, trading.maxBarterIngots);
        assertEquals(1, trading.barterDefaultIngots);
    }
}
