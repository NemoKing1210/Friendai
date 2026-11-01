package dev.friendai.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.provider.RoutingRule;
import dev.friendai.foundation.persistence.ProtectedConfigException;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoresTest {
    @TempDir
    Path dir;

    @Test
    void routingExtensionsSurviveUpgradeReorderSingleEditAndDeletion() throws IOException {
        Path path = dir.resolve("routing.json");
        Files.writeString(path, """
                {"version":14,"profiles":{"main":{"routing":[
                {"modelGlob":"first-*","protocol":"openai","extension":"A"},
                {"modelGlob":"second-*","protocol":"openai","extension":"B"}]}}}
                """);
        var store = ConfigStores.server(path);
        ServerConfig config = store.load();
        assertEquals("A", routing(path, 0).get("extension").getAsString());
        assertEquals("B", routing(path, 1).get("extension").getAsString());
        java.util.Collections.reverse(config.profiles.get("main").routing);
        store.save(config);
        assertEquals("B", routing(path, 0).get("extension").getAsString());
        config.profiles.get("main").routing.set(0, new RoutingRule("edited-*", "openai", null));
        store.save(config);
        assertEquals("B", routing(path, 0).get("extension").getAsString());
        config.profiles.get("main").routing.remove(0);
        store.save(config);
        assertEquals("first-*", routing(path, 0).get("modelGlob").getAsString());
        assertEquals("A", routing(path, 0).get("extension").getAsString());
        assertEquals(1, store.load().profiles.get("main").routing.size());
    }

    @Test
    void ambiguousEditsToArrayEntriesWithExtensionsRefuseToGuessTheirIdentity() throws IOException {
        Path path = dir.resolve("ambiguous-routing.json");
        Files.writeString(path, """
                {"version":16,"profiles":{"main":{"routing":[
                {"modelGlob":"first-*","protocol":"openai","extension":"A"},
                {"modelGlob":"second-*","protocol":"openai","extension":"B"}]}}}
                """);
        var store = ConfigStores.server(path);
        ServerConfig config = store.load();
        String original = Files.readString(path);
        config.profiles.get("main").routing.set(0, new RoutingRule("changed-a", "openai", null));
        config.profiles.get("main").routing.set(1, new RoutingRule("changed-b", "openai", null));
        assertThrows(ProtectedConfigException.class, () -> store.save(config));
        assertEquals(original, Files.readString(path));
    }

    private JsonObject routing(Path path, int index) throws IOException {
        return json(path)
                .getAsJsonObject("profiles")
                .getAsJsonObject("main")
                .getAsJsonArray("routing")
                .get(index)
                .getAsJsonObject();
    }

    private JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    @Test
    void schema18KeepsItsMemoryValuesAndGainsTheTranscriptDefaults() throws IOException {
        Path path = dir.resolve("server-18-memory.json");
        Files.writeString(path, "{\"version\":18,\"memory\":{\"enabled\":false,\"maxFactsPerPlayer\":12}}");
        ServerConfig config = ConfigStores.server(path).load();
        assertEquals(ServerConfig.VERSION, config.version);
        assertFalse(config.memory.enabled);
        assertEquals(12, config.memory.maxFactsPerPlayer);
        assertEquals(200, config.memory.transcriptMaxEntries);
        assertEquals(400, config.memory.transcriptMaxTextChars);
        assertEquals(7, config.memory.transcriptRetentionDays);
        assertEquals(90, config.memory.compactAtPercent);
        assertTrue(config.memory.compactionEnabled);
    }

    @Test
    void transcriptAndCompactionSettingsAreClamped() {
        ServerConfig config = ServerConfig.defaults();
        config.memory.transcriptMaxEntries = 1;
        config.memory.transcriptMaxTextChars = 99_999;
        config.memory.transcriptRetentionDays = 0;
        config.memory.compactAtPercent = 10;
        config.validate();
        assertEquals(20, config.memory.transcriptMaxEntries);
        assertEquals(2_000, config.memory.transcriptMaxTextChars);
        assertEquals(1, config.memory.transcriptRetentionDays);
        assertEquals(50, config.memory.compactAtPercent);
    }

    @Test
    void eachSupportedServerVersionPreservesProfilesAndExplicitChoices() throws IOException {
        for (int version = 3; version < 19; version++) {
            Path path = dir.resolve("server-" + version + ".json");
            String original = """
                    {"version":%d,"activeProfile":"custom","profiles":{"custom":{
                    "provider":"openai","model":"owner-model","apiKey":"test-secret",
                    "baseUrl":"https://example.test/v1","maxTokens":2048,"temperature":0.2,"extension":9}},
                    "companion":{"name":"Buddy"},"chat":{"listenAll":true},
                    "agent":{"maxReplans":0},"tasks":{"defaultFollow":false,
                    "resources":{"allowExpeditions":false,"observationRadius":24}},"extension":{"enabled":true}}
                    """.formatted(version);
            Files.writeString(path, original);
            var store = ConfigStores.server(path);
            ServerConfig config = store.load();
            assertEquals(ServerConfig.VERSION, config.version);
            assertEquals("custom", config.activeProfile);
            assertEquals("owner-model", config.profiles.get("custom").model);
            assertEquals("openai", config.profiles.get("custom").provider);
            assertEquals("test-secret", config.profiles.get("custom").apiKey);
            assertEquals("https://example.test/v1", config.profiles.get("custom").baseUrl);
            assertEquals(2048, config.profiles.get("custom").maxTokens);
            assertEquals(0.2, config.profiles.get("custom").temperature);
            assertEquals("Buddy", config.companion.name);
            assertTrue(config.chat.listenAll);
            assertFalse(config.tasks.defaultFollow);
            assertFalse(config.tasks.resources.allowExpeditions);
            assertEquals(24, config.tasks.resources.observationRadius);
            assertEquals(0, config.agent.maxReplans);
            assertEquals(2, config.agent.plannerRetries);
            assertEquals(120, config.tasks.freeTimeWatchdogSec);
            assertEquals(4, config.tasks.placeIntervalTicks);
            assertTrue(config.mining.protectDrops);
            assertEquals(9, config.mining.catchBlocks);
            assertEquals(200, config.memory.transcriptMaxEntries);
            assertEquals(90, config.memory.compactAtPercent);
            assertTrue(config.memory.compactionEnabled);
            assertFalse(store.hasUnsupportedVersion());
            assertFalse(store.hasProtectedRecovery());
            store.save(config);
            assertEquals(
                    9,
                    json(path)
                            .getAsJsonObject("profiles")
                            .getAsJsonObject("custom")
                            .get("extension")
                            .getAsInt());
            assertTrue(json(path).getAsJsonObject("extension").get("enabled").getAsBoolean());
            try (var files = Files.list(dir)) {
                List<Path> backups = files.filter(
                                p -> p.getFileName().toString().startsWith(path.getFileName() + ".v"))
                        .toList();
                assertEquals(1, backups.size());
                assertEquals(original, Files.readString(backups.getFirst()));
            }
        }
    }

    @Test
    void explicitMiningSettingsOfAnOldFileSurviveAndGarbageIsRepaired() throws IOException {
        Path path = dir.resolve("mining.json");
        Files.writeString(path, """
                {"version":17,"mining":{"protectDrops":false,"valuableBlocks":["minecraft:diamond_ore"],
                "catchBlocks":0,"extension":3}}
                """);
        ServerConfig config = ConfigStores.server(path).load();
        assertFalse(config.mining.protectDrops);
        assertEquals(List.of("minecraft:diamond_ore"), config.mining.valuableBlocks);
        assertEquals(0, config.mining.catchBlocks);

        Path garbage = dir.resolve("mining-garbage.json");
        Files.writeString(garbage, "{\"version\":17,\"mining\":{\"valuableBlocks\":null,\"catchBlocks\":99}}");
        ServerConfig repaired = ConfigStores.server(garbage).load();
        assertFalse(repaired.mining.valuableBlocks.isEmpty());
        assertEquals(27, repaired.mining.catchBlocks);
    }

    @Test
    void personaRenameKeepsTheNewFieldWhenBothArePresent() throws IOException {
        for (String fields : List.of(
                "\"persona\":\"Original personality\"", "\"persona\":\"Old text\",\"description\":\"New text\"")) {
            Path path = dir.resolve("persona.json");
            Files.writeString(path, "{\"version\":12,\"companion\":{" + fields + "}}");
            ServerConfig config = ConfigStores.server(path).load();
            assertEquals(
                    fields.contains("description") ? "New text" : "Original personality", config.companion.description);
            assertFalse(json(path).getAsJsonObject("companion").has("persona"));
        }
    }

    @Test
    void explicitNewSettingsInOldFilesAreNeverReset() throws IOException {
        Path path = dir.resolve("server.json");
        Files.writeString(path, """
                {"version":14,"agent":{"plannerRetries":4},"tasks":{"freeTimeWatchdogSec":240}}
                """);
        ServerConfig config = ConfigStores.server(path).load();
        assertEquals(4, config.agent.plannerRetries);
        assertEquals(240, config.tasks.freeTimeWatchdogSec);
    }

    @Test
    void deletingAProfileDoesNotResurrectItWhileUnknownFieldsOfRetainedProfilesSurvive() throws IOException {
        Path path = dir.resolve("profiles.json");
        Files.writeString(path, """
                {"version":14,"activeProfile":"keep","profiles":{
                "keep":{"apiKey":"test-secret","extension":9},"remove":{"apiKey":"old-secret"}}}
                """);
        var store = ConfigStores.server(path);
        var config = store.load();
        config.profiles.remove("remove");
        store.save(config);
        JsonObject profiles = json(path).getAsJsonObject("profiles");
        assertFalse(profiles.has("remove"));
        assertEquals(9, profiles.getAsJsonObject("keep").get("extension").getAsInt());
        assertFalse(store.load().profiles.containsKey("remove"));
    }

    @Test
    void clientV1PreservesRecognitionPreferencesAndDoesNotEnableSynthesis() throws IOException {
        Path path = dir.resolve("client-v1.json");
        Files.writeString(path, """
                {"version":1,"voice":{"enabled":false,"whisperModel":"small","language":"ru",
                "inputDevice":"Mic","maxRecordSec":45,"extension":7}}
                """);
        var store = ConfigStores.client(path);
        ClientConfig config = store.load();
        assertFalse(config.voice.stt.enabled);
        assertEquals("small", config.voice.stt.local.model);
        assertEquals("ru", config.voice.stt.language);
        assertEquals("Mic", config.voice.stt.inputDevice);
        assertEquals(45, config.voice.stt.maxRecordSec);
        assertFalse(config.voice.tts.enabled);
        assertEquals(ClientConfig.CURRENT_VERSION, config.version);
        assertFalse(json(path).getAsJsonObject("voice").has("whisperModel"));
        store.save(config);
        assertEquals(7, json(path).getAsJsonObject("voice").get("extension").getAsInt());
    }

    @Test
    void clientV4GetsTheSocialDefaultsWithoutLosingVoiceSettings() throws IOException {
        Path path = dir.resolve("client-v4.json");
        Files.writeString(path, """
                {"version":4,"voice":{"stt":{"enabled":true,"provider":"groq"},
                "tts":{"enabled":true,"provider":"local"}}}
                """);
        ClientConfig config = ConfigStores.client(path).load();
        assertEquals(ClientConfig.CURRENT_VERSION, config.version);
        assertTrue(config.voice.stt.enabled);
        assertEquals("groq", config.voice.stt.provider);
        assertTrue(config.voice.tts.enabled);
        assertTrue(config.social.outline, "the outline is on until the player turns it off");
        assertTrue(config.social.moodMark, "the mood mark is on until the player turns it off");
        ConfigStores.client(path).save(config);
        assertTrue(json(path).getAsJsonObject("social").get("outline").getAsBoolean());
    }

    @Test
    void clientV2MovesNestedProviderSettingsWithoutLosingCredentialsOrMute() throws IOException {
        Path path = dir.resolve("client-v2.json");
        Files.writeString(path, """
                {"version":2,"voice":{"whisperModel":"tiny","tts":{"enabled":true,"provider":"openai",
                "volume":0,"speakGuests":true,"outputDevice":"Speakers",
                "local":{"voice":"vits-piper-ru_RU-dmitri-medium","speed":1.2},
                "openai":{"apiKey":"test-secret","model":"custom","voice":"nova",
                "baseUrl":"https://example.test/v1","speed":1.1,"extension":7},
                "elevenlabs":{"apiKey":"second-secret","voice":"other"}}}}
                """);
        ClientConfig config = ConfigStores.client(path).load();
        assertEquals("tiny", config.voice.stt.local.model);
        assertTrue(config.voice.tts.enabled);
        assertEquals("openai", config.voice.tts.provider);
        assertEquals(0, config.voice.tts.volume);
        assertTrue(config.voice.tts.speakGuests);
        assertEquals("Speakers", config.voice.tts.outputDevice);
        assertEquals("ru_RU-dmitri-medium", config.voice.tts.local.voice);
        assertEquals(1.2, config.voice.tts.local.speed);
        assertEquals("test-secret", config.voice.tts.cloud.get("openai").apiKey);
        assertEquals("custom", config.voice.tts.cloud.get("openai").model);
        assertEquals("nova", config.voice.tts.cloud.get("openai").voice);
        assertEquals("https://example.test/v1", config.voice.tts.cloud.get("openai").baseUrl);
        assertEquals(1.1, config.voice.tts.cloud.get("openai").speed);
        assertEquals("second-secret", config.voice.tts.cloud.get("elevenlabs").apiKey);
        JsonObject tts = json(path).getAsJsonObject("voice").getAsJsonObject("tts");
        assertFalse(tts.has("openai"));
        assertEquals(
                7,
                tts.getAsJsonObject("cloud")
                        .getAsJsonObject("openai")
                        .get("extension")
                        .getAsInt());
    }

    @Test
    void clientV2AndV3KeepProviderMapsAndExplicitSpatialPreference() throws IOException {
        for (int version : List.of(2, 3)) {
            Path path = dir.resolve("client-" + version + ".json");
            Files.writeString(path, """
                    {"version":%d,"voice":{"stt":{"provider":"groq","cloud":{"groq":{"apiKey":"groq-secret"}}},
                    "tts":{"provider":"yandex","spatial":false,"cloud":{"yandex":{
                    "apiKey":"yandex-secret","folderId":"folder","voice":"filipp"}}}}}
                    """.formatted(version));
            ClientConfig config = ConfigStores.client(path).load();
            assertEquals("groq", config.voice.stt.provider);
            assertEquals("groq-secret", config.voice.stt.cloud.get("groq").apiKey);
            assertEquals("yandex", config.voice.tts.provider);
            assertFalse(config.voice.tts.spatial);
            assertEquals("yandex-secret", config.voice.tts.cloud.get("yandex").apiKey);
            assertEquals("folder", config.voice.tts.cloud.get("yandex").folderId);
            assertEquals("filipp", config.voice.tts.cloud.get("yandex").voice);
        }
    }

    @Test
    void unrecognizedServerAndClientVersionsAreNotGuessed() throws IOException {
        Path path = dir.resolve("unknown.json");
        for (int version : List.of(1, 2, ServerConfig.VERSION + 1)) {
            String source = "{\"version\":" + version + "}";
            Files.writeString(path, source);
            assertThrows(UnsupportedStoreVersionException.class, ConfigStores.server(path)::load);
            assertEquals(source, Files.readString(path));
        }
        String futureClient = "{\"version\":" + (ClientConfig.CURRENT_VERSION + 1) + "}";
        Files.writeString(path, futureClient);
        assertThrows(UnsupportedStoreVersionException.class, ConfigStores.client(path)::load);
        assertEquals(futureClient, Files.readString(path));
    }
}
