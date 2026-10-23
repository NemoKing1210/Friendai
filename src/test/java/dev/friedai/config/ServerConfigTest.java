package dev.friedai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friedai.llm.provider.LlmProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConfigTest {
    @Test
    void defaultsMatchSpec() {
        ServerConfig c = ServerConfig.defaults();
        assertEquals("main", c.activeProfile);
        assertEquals("auto", c.companion.language);
        assertEquals("@", c.chat.prefix);
        assertEquals(8, c.brain.maxToolStepsPerTurn);
        assertEquals(30, c.brain.historyMessages);
        assertEquals(32, c.tasks.mineSearchRadius);
    }

    @Test
    void validateClampsRanges() {
        ServerConfig c = ServerConfig.defaults();
        c.brain.maxToolStepsPerTurn = 100;
        List<String> problems = c.validate();
        assertEquals(32, c.brain.maxToolStepsPerTurn);
        assertEquals(1, problems.size());
        c.brain.historyMessages = 2;
        c.validate();
        assertEquals(6, c.brain.historyMessages);
    }

    @Test
    void toLlmProfileResolvesEnvKey() {
        ServerConfig c = ServerConfig.defaults();
        LlmProfile p = c.toLlmProfile(Map.of("ANTHROPIC_API_KEY", "secret")::get);
        assertEquals("anthropic", p.provider());
        assertEquals("secret", p.apiKey());
        assertTrue(!p.toString().contains("secret"));
    }

    @Test
    void guestDefaults() {
        var c = ServerConfig.defaults();
        assertTrue(c.chat.guestsEnabled);
        assertEquals(16, c.chat.hearingRadius);
        assertEquals(5000, c.chat.guestCooldownMs);
        assertEquals(2, c.version);
    }

    @Test
    void validateClampsGuestSettings() {
        var c = ServerConfig.defaults();
        c.chat.hearingRadius = 500;
        c.chat.guestCooldownMs = -1;
        c.validate();
        assertEquals(64, c.chat.hearingRadius);
        assertEquals(0, c.chat.guestCooldownMs);
    }

    @Test
    void version1FileMigratesWithDefaults(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("s.json");
        Files.writeString(f, "{\"version\":1,\"chat\":{\"prefix\":\"!\"}}");
        var store = new ConfigStore<>(f, ServerConfig.class, ServerConfig::defaults, ServerConfig.VERSION, ServerConfig::migrate);
        ServerConfig c = store.load();
        assertEquals("!", c.chat.prefix);
        assertEquals(16, c.chat.hearingRadius);
        assertTrue(Files.readString(f).contains("\"version\": 2"));
    }

    @Test
    void missingActiveProfileFails() {
        ServerConfig c = ServerConfig.defaults();
        c.activeProfile = "ghost";
        ConfigException e = assertThrows(ConfigException.class, () -> c.toLlmProfile(k -> null));
        assertEquals("friedai.error.config.profile_missing", e.userMessage().key());
    }
}
