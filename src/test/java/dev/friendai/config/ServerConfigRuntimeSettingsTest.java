package dev.friendai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ServerConfigRuntimeSettingsTest {

    @Test
    void freeTimeWatchdogHasBoundedDefaultAndMigrationValue() {
        ServerConfig config = ServerConfig.defaults();
        assertEquals(120, config.tasks.freeTimeWatchdogSec);

        config.tasks.freeTimeWatchdogSec = 0;
        config.validate();
        assertEquals(1, config.tasks.freeTimeWatchdogSec);

        var migrated = ServerConfig.migrate(
                JsonParser.parseString("{\"version\":15,\"tasks\":{\"taskTimeoutSec\":60}}")
                        .getAsJsonObject(),
                15);
        assertEquals(
                120,
                migrated.getAsJsonObject("tasks").get("freeTimeWatchdogSec").getAsInt());
        assertEquals(16, ServerConfig.VERSION);
    }
}
