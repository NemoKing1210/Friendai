package dev.friendai.server.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.config.ServerConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeSettingsIsolationTest {

    @Test
    void applyingSettingsKeepsOtherRuntimeSnapshotsAndConfigInputsIsolated() {
        ServerConfiguration first = new ServerConfiguration(ignored -> {});
        ServerConfiguration second = new ServerConfiguration(ignored -> {});

        ServerConfig firstConfig = ServerConfig.defaults();
        firstConfig.tasks.taskTimeoutSec = 45;
        firstConfig.pathing.breakBlacklist = List.of("minecraft:diamond_block");
        first.apply(firstConfig);
        ServerSettings firstSnapshot = first.current();

        ServerConfig secondConfig = ServerConfig.defaults();
        secondConfig.tasks.taskTimeoutSec = 90;
        secondConfig.pathing.breakBlacklist = List.of("minecraft:emerald_block");
        second.apply(secondConfig);

        secondConfig.tasks.taskTimeoutSec = 1;
        secondConfig.pathing.breakBlacklist = List.of();

        assertEquals(45, first.current().tasks().taskTimeoutSec());
        assertEquals(45, firstSnapshot.tasks().taskTimeoutSec());
        assertTrue(firstSnapshot.breakList().matches("minecraft:diamond_block", java.util.Set.of()));
        assertFalse(firstSnapshot.breakList().matches("minecraft:emerald_block", java.util.Set.of()));
        assertEquals(90, second.current().tasks().taskTimeoutSec());
        assertTrue(second.current().breakList().matches("minecraft:emerald_block", java.util.Set.of()));
    }
}
