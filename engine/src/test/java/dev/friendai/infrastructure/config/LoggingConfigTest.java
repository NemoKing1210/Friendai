package dev.friendai.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.features.settings.SettingsModel;
import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogLevel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoggingConfigTest {
    @TempDir
    Path dir;

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    @Test
    void serverSchema20KeepsValuesAndGainsLoggingDefaults() throws IOException {
        Path path = dir.resolve("server.json");
        Files.writeString(path, "{\"version\":20,\"chat\":{\"prefix\":\"!\"},\"future\":{\"a\":1}}");
        var store = ConfigStores.server(path);
        ServerConfig config = store.load();
        assertEquals("!", config.chat.prefix);
        assertEquals("normal", config.logging.level);
        assertEquals(8_388_608L, config.logging.maxFileBytes);
        assertTrue(config.logging.digest);
        store.save(config);
        JsonObject saved = json(path);
        assertEquals("!", saved.getAsJsonObject("chat").get("prefix").getAsString());
        assertTrue(saved.has("future"));
        assertEquals("normal", saved.getAsJsonObject("logging").get("level").getAsString());
    }

    @Test
    void clientSchema7KeepsValuesAndGainsLoggingDefaults() throws IOException {
        Path path = dir.resolve("client.json");
        Files.writeString(path, "{\"version\":7,\"social\":{\"outline\":true},\"future\":2}");
        var store = ConfigStores.client(path);
        ClientConfig config = store.load();
        assertEquals("normal", config.logging.level);
        store.save(config);
        JsonObject saved = json(path);
        assertEquals("normal", saved.getAsJsonObject("logging").get("level").getAsString());
        assertTrue(saved.has("future"));
        assertFalse(saved.getAsJsonObject("social").has("outline"));
    }

    @Test
    void validateRepairsAHandEditedSection() {
        ServerConfig config = ServerConfig.defaults();
        config.logging.level = "loud";
        config.logging.maxFileBytes = 1L;
        config.logging.maxTotalBytes = 1L;
        config.logging.retentionDays = 0;
        config.logging.maxSessionsPerOwner = 0;
        List<String> problems = config.validate();
        assertEquals("normal", config.logging.level);
        assertEquals(1_048_576L, config.logging.maxFileBytes);
        assertEquals(1_048_576L, config.logging.maxTotalBytes);
        assertEquals(1, config.logging.retentionDays);
        assertEquals(1, config.logging.maxSessionsPerOwner);
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("logging.level")));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("logging.maxFileBytes")));
    }

    @Test
    void uncheckingEveryCategoryTurnsFileLoggingOff() {
        ServerConfig config = ServerConfig.defaults();
        config.logging.brain = false;
        config.logging.tool = false;
        config.logging.task = false;
        config.logging.queue = false;
        config.logging.net = false;
        config.logging.speech = false;
        config.logging.world = false;
        config.logging.config = false;
        config.logging.client = false;
        config.logging.server = false;
        assertNull(config.logging.toSettings().level());
    }

    @Test
    void levelsMapToSeverityAndBodies() {
        ServerConfig config = ServerConfig.defaults();
        assertEquals(LogLevel.INFO, config.logging.toSettings().level());
        assertFalse(config.logging.toSettings().includesBodies());
        config.logging.level = "detailed";
        assertEquals(LogLevel.DEBUG, config.logging.toSettings().level());
        assertTrue(config.logging.toSettings().includesBodies());
        config.logging.level = "off";
        assertNull(config.logging.toSettings().level());
        config.logging.level = "trace";
        assertEquals(LogLevel.TRACE, config.logging.toSettings().level());
    }

    @Test
    void categoryTogglesBecomeTheAllowedSet() {
        ServerConfig config = ServerConfig.defaults();
        config.logging.net = false;
        config.logging.client = false;
        var settings = config.logging.toSettings();
        assertFalse(settings.categories().contains(LogCategory.NET));
        assertFalse(settings.categories().contains(LogCategory.CLIENT));
        assertTrue(settings.categories().contains(LogCategory.BRAIN));
        assertTrue(settings.categories().contains(LogCategory.SERVER));
    }

    @Test
    void clientSettingsKeepClientAndSpeechOnly() {
        ClientConfig config = ClientConfig.defaults();
        var settings = config.logging.toSettings();
        assertEquals(LogLevel.INFO, settings.level());
        assertEquals(java.util.Set.of(LogCategory.CLIENT, LogCategory.SPEECH), settings.categories());
    }

    @Test
    void theLogsCategoryIsCataloguedLastAndAdvanced() {
        List<String> categories = SettingsModel.categories();
        assertEquals("logs", categories.get(categories.size() - 1));
        assertTrue(SettingsModel.show("friendai.config.logs.level", false));
        assertFalse(SettingsModel.show("friendai.config.logs.retention_days", false));
        assertTrue(SettingsModel.show("friendai.config.logs.retention_days", true));
        assertTrue(SettingsModel.search("retention").contains("friendai.config.logs.retention_days"));
    }
}
