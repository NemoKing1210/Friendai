package dev.friendai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.autonomy.AutonomySettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConfigAutonomyTest {
    @Test
    void defaultsMatchSpec() {
        assertEquals(AutonomySettings.DEFAULTS, new ServerConfig().autonomy.toSettings());
    }

    @Test
    void clampsOutOfRange() {
        ServerConfig config = new ServerConfig();
        config.autonomy.idleMinutes = 0;
        config.autonomy.resumeAfterSec = 1;
        config.autonomy.shiftMinutes = 500;
        config.autonomy.maxShiftsPerHour = 0;
        config.autonomy.offlineShiftsPerHour = -1;
        config.autonomy.failCooldownMin = 9_999;
        List<String> keep = new ArrayList<>();
        keep.add("not an id!");
        for (int i = 0; i < 40; i++) {
            keep.add("minecraft:item_" + i);
        }
        config.autonomy.keepItems = keep;
        List<String> problems = config.validate();
        assertEquals(1, config.autonomy.idleMinutes);
        assertEquals(10, config.autonomy.resumeAfterSec);
        assertEquals(120, config.autonomy.shiftMinutes);
        assertEquals(1, config.autonomy.maxShiftsPerHour);
        assertEquals(0, config.autonomy.offlineShiftsPerHour);
        assertEquals(240, config.autonomy.failCooldownMin);
        assertEquals(32, config.autonomy.keepItems.size());
        assertEquals("minecraft:item_0", config.autonomy.keepItems.get(0));
        assertTrue(problems.stream().anyMatch(p -> p.contains("autonomy.keepItems")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("autonomy.idleMinutes")));
    }

    @Test
    void missingKeepItemsGetDefaults() {
        ServerConfig config = new ServerConfig();
        config.autonomy.keepItems = null;
        config.validate();
        assertEquals(AutonomySettings.DEFAULT_KEEP, config.autonomy.keepItems);
    }

    @Test
    void missingSectionMigrates(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("c.json"), "{\"version\":11}");
        ServerConfig loaded = new ConfigStore<>(
                        temp.resolve("c.json"),
                        ServerConfig.class,
                        ServerConfig::new,
                        ServerConfig.VERSION,
                        ServerConfig::migrate)
                .load();
        assertEquals(AutonomySettings.DEFAULTS, loaded.autonomy.toSettings());
        assertEquals(ServerConfig.VERSION, loaded.version);
    }
}
