package dev.friendai.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.foundation.persistence.ProtectedConfigException;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {
    static class Sample {
        int version = 1;
        String name = "default";
        String added = "none";
    }

    @TempDir
    Path dir;

    private ConfigStore<Sample> store() {
        return new ConfigStore<>(dir.resolve("friendai-server.json"), Sample.class, Sample::new, 1);
    }

    @Test
    void missingFileWritesDefaults() throws IOException {
        Sample s = store().load();
        assertEquals("default", s.name);
        JsonObject written = JsonParser.parseString(Files.readString(dir.resolve("friendai-server.json")))
                .getAsJsonObject();
        assertEquals("default", written.get("name").getAsString());
        assertEquals(1, written.get("version").getAsInt());
    }

    @Test
    void corruptFileBackedUpAndDefaultsUsed() throws IOException {
        Files.writeString(dir.resolve("friendai-server.json"), "{oops");
        Sample s = store().load();
        assertEquals("default", s.name);
        assertEquals("{oops", Files.readString(dir.resolve("friendai-server.json.bak")));
    }

    @Test
    void failedBackupKeepsTheOriginalAndRefusesEveryWrite() throws IOException {
        Path file = dir.resolve("friendai-server.json");
        String broken = "{oops";
        Files.writeString(file, broken);
        Files.createDirectory(dir.resolve("friendai-server.json.bak"));

        ConfigStore<Sample> store = store();
        Sample defaults = store.load();

        assertEquals("default", defaults.name);
        assertTrue(store.recoveredOnLastLoad());
        assertTrue(store.hasProtectedRecovery(), "a failed backup must leave recovery read-only");
        assertEquals(broken, Files.readString(file), "the damaged original is the only copy left");
        Sample edited = new Sample();
        edited.name = "would overwrite";
        ProtectedConfigException refused = assertThrows(ProtectedConfigException.class, () -> store.save(edited));
        assertFalse(refused.getMessage().contains("oops"), "diagnostics must not expose configuration values");
        assertEquals(broken, Files.readString(file));
    }

    @Test
    void aLaterLoadRecoversOnceTheBackupPathWorksAgain() throws IOException {
        Path file = dir.resolve("friendai-server.json");
        Files.writeString(file, "{oops");
        Path backup = dir.resolve("friendai-server.json.bak");
        Files.createDirectory(backup);
        ConfigStore<Sample> store = store();
        store.load();
        assertTrue(store.hasProtectedRecovery());

        Files.delete(backup);
        Sample recovered = store.load();

        assertEquals("default", recovered.name);
        assertFalse(store.hasProtectedRecovery(), "a successful backup makes the store writable again");
        assertEquals("{oops", Files.readString(backup));
        JsonObject written = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals("default", written.get("name").getAsString(), "defaults replace the broken file after a backup");
    }

    @Test
    void saveIsAtomicAndRoundTrips() throws IOException {
        ConfigStore<Sample> store = store();
        Sample s = new Sample();
        s.name = "custom";
        store.save(s);
        assertEquals("custom", store.load().name);
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(
                    List.of("friendai-server.json"),
                    files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void recoveredFlagFollowsCorruption() throws IOException {
        Files.writeString(dir.resolve("friendai-server.json"), "{oops");
        ConfigStore<Sample> store = store();
        store.load();
        assertTrue(store.recoveredOnLastLoad());
        store.load();
        assertFalse(store.recoveredOnLastLoad());
    }

    @Test
    void olderVersionIsPreservedAndBlocksWrites() throws IOException {
        Files.writeString(dir.resolve("friendai-server.json"), "{\"version\":0,\"name\":\"old\"}");
        String original = Files.readString(dir.resolve("friendai-server.json"));
        ConfigStore<Sample> store = store();

        assertThrows(UnsupportedStoreVersionException.class, store::load);
        assertThrows(UnsupportedStoreVersionException.class, () -> store.save(new Sample()));
        assertEquals(original, Files.readString(dir.resolve("friendai-server.json")));
    }

    @Test
    void fallbackOnUnsupportedVersionUsesMemoryDefaultsAndPreservesFile() throws IOException {
        for (int version : List.of(0, 2)) {
            Path file = dir.resolve("server-" + version + ".json");
            String original = "{\"version\":" + version + ",\"name\":\"custom\"}";
            Files.writeString(file, original);
            ConfigStore<Sample> store = new ConfigStore<>(file, Sample.class, Sample::new, 1);

            Sample fallback = store.loadWithDefaultsOnUnsupportedVersion();

            assertEquals("default", fallback.name);
            assertTrue(store.hasUnsupportedVersion());
            assertEquals(original, Files.readString(file));
            assertThrows(UnsupportedStoreVersionException.class, () -> store.save(new Sample()));
            assertEquals(original, Files.readString(file));
        }
    }
}
