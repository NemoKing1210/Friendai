package dev.friendai.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
        return new ConfigStore<>(dir.resolve("friendai-server.json"), Sample.class, Sample::new, 1, (json, from) -> {
            json.addProperty("added", "migrated");
            return json;
        });
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
    void olderVersionMigrated() throws IOException {
        Files.writeString(dir.resolve("friendai-server.json"), "{\"version\":0,\"name\":\"old\"}");
        Sample s = store().load();
        assertEquals("migrated", s.added);
        assertEquals("old", s.name);
        JsonObject saved = JsonParser.parseString(Files.readString(dir.resolve("friendai-server.json")))
                .getAsJsonObject();
        assertEquals(1, saved.get("version").getAsInt());
        assertTrue(saved.has("added"));
    }
}
