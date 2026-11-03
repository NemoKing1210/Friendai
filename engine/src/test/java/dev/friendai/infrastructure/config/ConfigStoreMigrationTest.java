package dev.friendai.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.foundation.persistence.ProtectedConfigException;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreMigrationTest {
    @TempDir
    Path dir;

    @Test
    void aFileChangedDuringBackupIsNeverReplacedByTheStaleMigration() throws IOException {
        String original = "{\"version\":1,\"oldName\":\"custom\"}";
        String external = "{\"version\":4,\"name\":\"changed elsewhere\"}";
        Files.writeString(file(), original);
        var operations = new ConfigFileOperations() {
            @Override
            Path backup(Path path, byte[] bytes, int version) throws IOException {
                Path backup = super.backup(path, bytes, version);
                Files.writeString(path, external);
                return backup;
            }
        };
        var store = store(operations);
        store.load();
        assertTrue(store.hasProtectedRecovery());
        assertEquals(external, Files.readString(file()));
        assertEquals(original, Files.readString(backups().getFirst()));
    }

    @Test
    void malformedUtf8IsPreservedAndProtectedInsteadOfRewritingAReplacementCharacter() throws IOException {
        byte[] original = "{\"version\":1,\"oldName\":\"x\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        original[original.length - 3] = (byte) 0xff;
        Files.write(file(), original);
        var store = store(new ConfigFileOperations());
        store.load();
        assertTrue(store.hasProtectedRecovery());
        assertThrows(ProtectedConfigException.class, () -> store.save(new Settings()));
        assertArrayEquals(original, Files.readAllBytes(file()));
        assertTrue(backups().isEmpty());
    }

    static class Settings {
        int version = 3;
        String name = "default";
        int limit = 20;
    }

    private ConfigMigration migration() {
        return new ConfigMigration(
                1, List.of(json -> json.add("name", json.remove("oldName")), json -> json.addProperty("limit", 42)));
    }

    private Path file() {
        return dir.resolve("settings.json");
    }

    private ConfigStore<Settings> store(ConfigFileOperations operations) {
        return new ConfigStore<>(
                file(),
                Settings.class,
                Settings::new,
                3,
                migration(),
                value -> {
                    if (value.name == null) throw new IllegalArgumentException("test-secret");
                },
                operations);
    }

    private List<Path> backups() throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".bak")).toList();
        }
    }

    @Test
    void upgradeBacksUpExactBytesPreservesUnknownFieldsAndDoesNotRepeat() throws IOException {
        String original = "{ \"version\":1, \"oldName\":\"Custom Ω\", \"extra\":{\"x\":7} }\r\n";
        Files.writeString(file(), original);
        var store = store(new ConfigFileOperations());
        Settings loaded = store.load();
        assertEquals("Custom Ω", loaded.name);
        assertEquals(42, loaded.limit);
        assertEquals(3, loaded.version);
        assertEquals(original, Files.readString(backups().getFirst()));
        assertEquals(1, backups().size());
        JsonObject json = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
        assertFalse(json.has("oldName"));
        assertEquals(7, json.getAsJsonObject("extra").get("x").getAsInt());
        loaded.name = "Changed";
        store.save(loaded);
        store.load();
        assertEquals(1, backups().size());
        assertEquals(
                7,
                JsonParser.parseString(Files.readString(file()))
                        .getAsJsonObject()
                        .getAsJsonObject("extra")
                        .get("x")
                        .getAsInt());
        assertEquals("Changed", store(new ConfigFileOperations()).load().name);
    }

    @Test
    void everyStepRunsInOrderOnACopyAndUnknownVersionsAreRejected() {
        JsonObject original =
                JsonParser.parseString("{\"version\":1,\"oldName\":\"custom\"}").getAsJsonObject();
        JsonObject migrated = migration().apply(original, 1, 3);
        assertEquals("custom", migrated.get("name").getAsString());
        assertTrue(original.has("oldName"));
        assertEquals(1, original.get("version").getAsInt());
        assertThrows(UnsupportedStoreVersionException.class, () -> migration().apply(original, 0, 3));
        assertThrows(UnsupportedStoreVersionException.class, () -> migration().apply(original, 1, 4));
        assertThrows(UnsupportedStoreVersionException.class, () -> migration().apply(original, 4, 3));
    }

    @Test
    void failedBackupLeavesOriginalReadOnlyAndReloadRetries() throws IOException {
        String original = "{\"version\":1,\"oldName\":\"custom\"}";
        Files.writeString(file(), original);
        var operations = new ConfigFileOperations() {
            boolean fail = true;

            @Override
            Path backup(Path path, byte[] bytes, int version) throws IOException {
                if (fail) throw new IOException("test-secret");
                return super.backup(path, bytes, version);
            }
        };
        var store = store(operations);
        assertEquals("default", store.load().name);
        assertTrue(store.hasProtectedRecovery());
        assertFalse(store.hasUnsupportedVersion());
        assertThrows(ProtectedConfigException.class, () -> store.save(new Settings()));
        assertEquals(original, Files.readString(file()));
        assertTrue(backups().isEmpty());
        operations.fail = false;
        assertEquals("custom", store.load().name);
        assertFalse(store.hasProtectedRecovery());
    }

    @Test
    void failedReplacementPreservesOriginalAndBackupAndRetryNeverOverwritesBackup() throws IOException {
        String original = "{\"version\":1,\"oldName\":\"custom\"}";
        Files.writeString(file(), original);
        var operations = new ConfigFileOperations() {
            boolean fail = true;

            @Override
            void replace(Path path, byte[] bytes) throws IOException {
                if (fail) throw new IOException("test-secret");
                super.replace(path, bytes);
            }
        };
        var store = store(operations);
        store.load();
        assertTrue(store.hasProtectedRecovery());
        assertEquals(original, Files.readString(file()));
        Path firstBackup = backups().getFirst();
        assertEquals(original, Files.readString(firstBackup));
        operations.fail = false;
        assertEquals("custom", store.load().name);
        assertEquals(2, backups().size());
        assertEquals(original, Files.readString(firstBackup));
    }

    @Test
    void failedTransformationOrValidationNeverUsesCorruptionRecovery() throws IOException {
        String original = "{\"version\":1,\"oldName\":null}";
        Files.writeString(file(), original);
        var store = store(new ConfigFileOperations());
        assertEquals("default", store.load().name);
        assertTrue(store.hasProtectedRecovery());
        assertFalse(store.recoveredOnLastLoad());
        assertEquals(original, Files.readString(file()));
        assertTrue(backups().isEmpty());

        var broken = new ConfigStore<>(
                file(),
                Settings.class,
                Settings::new,
                3,
                new ConfigMigration(
                        1,
                        List.of(
                                json -> {
                                    throw new IllegalStateException("test-secret");
                                },
                                json -> {})),
                value -> {});
        broken.load();
        assertTrue(broken.hasProtectedRecovery());
        assertEquals(original, Files.readString(file()));
        assertTrue(backups().isEmpty());
    }

    @Test
    void invalidUnknownAndFutureVersionsStayUntouchedAndExternalRepairClearsTheLatch() throws IOException {
        for (String version : List.of("0", "4", "3.5", "\"3\"", "null", "true", "4294967299")) {
            String original = "{\"version\":" + version + ",\"name\":\"custom\"}";
            Files.writeString(file(), original);
            var store = store(new ConfigFileOperations());
            assertThrows(UnsupportedStoreVersionException.class, store::load);
            assertThrows(UnsupportedStoreVersionException.class, () -> store.save(new Settings()));
            assertEquals(original, Files.readString(file()));
            Files.writeString(file(), "{\"version\":3,\"name\":\"fixed\"}");
            assertEquals("fixed", store.load().name);
            assertFalse(store.hasUnsupportedVersion());
            store.save(new Settings());
        }
        Files.writeString(file(), "{\"name\":\"unversioned\"}");
        assertThrows(UnsupportedStoreVersionException.class, store(new ConfigFileOperations())::load);
        assertTrue(backups().isEmpty());
    }

    @Test
    void saveCannotBypassMigrationOrExternalFutureVersionOrCorruption() throws IOException {
        var store = store(new ConfigFileOperations());
        for (String original : List.of("{\"version\":1,\"oldName\":\"custom\"}", "{\"version\":4}", "{oops")) {
            Files.writeString(file(), original);
            assertThrows(RuntimeException.class, () -> store.save(new Settings()));
            assertEquals(original, Files.readString(file()));
            Files.writeString(file(), "{\"version\":3}");
            store.load();
        }
    }

    @Test
    void oversizedConfigStaysProtectedWithoutReadingItIntoMemory() throws IOException {
        try (var out = Files.newOutputStream(file())) {
            out.write(new byte[8 * 1024 * 1024 + 1]);
        }
        var store = store(new ConfigFileOperations());
        store.load();
        assertTrue(store.hasProtectedRecovery());
        assertThrows(ProtectedConfigException.class, () -> store.save(new Settings()));
        assertEquals(8 * 1024 * 1024 + 1, Files.size(file()));
        assertTrue(backups().isEmpty());
    }
}
