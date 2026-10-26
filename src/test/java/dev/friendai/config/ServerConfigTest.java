package dev.friendai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.llm.provider.LlmProfile;
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
    void nullPathingListsFallBackToDefaults() {
        // A hand-edited file with "breakBlacklist": null must not crash the server state later on.
        ServerConfig c = ServerConfig.defaults();
        c.pathing.breakBlacklist = null;
        c.pathing.placeBlacklist = null;
        List<String> problems = c.validate();
        assertNotNull(c.pathing.breakBlacklist);
        assertNotNull(c.pathing.placeBlacklist);
        assertTrue(c.pathing.breakBlacklist.contains("#minecraft:planks"));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("pathing.breakBlacklist")));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("pathing.placeBlacklist")));
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
    void resourcePolicyDefaultsAndCapsAreSafe() {
        ServerConfig c = ServerConfig.defaults();
        assertTrue(c.tasks.resources.allowExpeditions);
        assertEquals(64, c.tasks.resources.localRadius);
        assertEquals(2_400, c.tasks.resources.localTicks);
        assertEquals(512, c.tasks.resources.expeditionRadius);
        assertEquals(12_000, c.tasks.resources.expeditionTicks);
        assertEquals(16, c.tasks.resources.observationRadius);
        c.tasks.resources.localRadius = 1;
        c.tasks.resources.expeditionRadius = 1;
        c.tasks.resources.localTicks = 1;
        c.tasks.resources.expeditionTicks = 1;
        c.tasks.resources.observationRadius = 100;
        c.validate();
        assertEquals(16, c.tasks.resources.localRadius);
        assertEquals(16, c.tasks.resources.expeditionRadius);
        assertEquals(200, c.tasks.resources.localTicks);
        assertEquals(200, c.tasks.resources.expeditionTicks);
        assertEquals(32, c.tasks.resources.observationRadius);
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
        assertEquals(32, c.chat.hearingRadius);
        assertEquals(5000, c.chat.guestCooldownMs);
        assertEquals(8, c.version);
    }

    @Test
    void memoryDefaults() {
        var c = ServerConfig.defaults();
        assertTrue(c.memory.enabled);
        assertEquals(160, c.memory.maxFactChars);
        assertEquals(20, c.memory.maxFactsPerPlayer);
        assertEquals(30, c.memory.maxWorldFacts);
        assertEquals(8, c.version);
        assertEquals(160, c.memory.limits().maxFactChars());
    }

    @Test
    void validateClampsMemoryLimits() {
        var c = ServerConfig.defaults();
        c.memory.maxFactChars = 5;
        c.memory.maxFactsPerPlayer = 0;
        c.memory.maxWorldFacts = 999;
        c.validate();
        assertEquals(40, c.memory.maxFactChars);
        assertEquals(1, c.memory.maxFactsPerPlayer);
        assertEquals(200, c.memory.maxWorldFacts);
    }

    @Test
    void version2FileMigratesWithMemoryDefaults(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("s.json");
        Files.writeString(f, "{\"version\":2,\"chat\":{\"hearingRadius\":8}}");
        var store = new ConfigStore<>(
                f, ServerConfig.class, ServerConfig::defaults, ServerConfig.VERSION, ServerConfig::migrate);
        ServerConfig c = store.load();
        assertEquals(8, c.chat.hearingRadius);
        assertEquals(160, c.memory.maxFactChars);
        assertTrue(c.memory.enabled);
        assertTrue(Files.readString(f).contains("\"version\": 8"));
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
        assertEquals(32, c.chat.hearingRadius);
        assertTrue(Files.readString(f).contains("\"version\": 8"));
    }

    @Test
    void version6FileMigratesWithResourceDefaults(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("s.json");
        Files.writeString(f, "{\"version\":6,\"chat\":{\"hearingRadius\":12},\"companion\":{\"chunkLoading\":{\"enabled\":false}}}");
        var store = new ConfigStore<>(f, ServerConfig.class, ServerConfig::defaults, ServerConfig.VERSION, ServerConfig::migrate);
        ServerConfig c = store.load();
        assertEquals(12, c.chat.hearingRadius);
        assertFalse(c.companion.chunkLoading.enabled);
        assertTrue(c.tasks.resources.allowExpeditions);
        assertEquals(64, c.tasks.resources.localRadius);
        assertEquals(2_400, c.tasks.resources.localTicks);
        assertTrue(Files.readString(f).contains("\"version\": 8"));
    }

    @Test
    void version7FileEnablesExpeditionsOnMigration(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("s.json");
        Files.writeString(f, "{\"version\":7,\"tasks\":{\"resources\":{\"allowExpeditions\":false}}}");
        var store = new ConfigStore<>(f, ServerConfig.class, ServerConfig::defaults, ServerConfig.VERSION, ServerConfig::migrate);
        ServerConfig c = store.load();
        assertTrue(c.tasks.resources.allowExpeditions);
        assertTrue(Files.readString(f).contains("\"version\": 8"));
        assertTrue(Files.readString(f).contains("\"allowExpeditions\": true"));
    }

    @Test
    void version8FileKeepsExpeditionsExplicitlyDisabled(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("s.json");
        Files.writeString(f, "{\"version\":8,\"tasks\":{\"resources\":{\"allowExpeditions\":false}}}");
        var store = new ConfigStore<>(f, ServerConfig.class, ServerConfig::defaults, ServerConfig.VERSION, ServerConfig::migrate);
        assertFalse(store.load().tasks.resources.allowExpeditions);
    }

    @Test
    void missingActiveProfileFails() {
        ServerConfig c = ServerConfig.defaults();
        c.activeProfile = "ghost";
        ConfigException e = assertThrows(ConfigException.class, () -> c.toLlmProfile(k -> null));
        assertEquals("friendai.error.config.profile_missing", e.userMessage().key());
    }

    @Test
    void defaultsIncludePathing() {
        var c = ServerConfig.defaults();
        assertTrue(c.pathing.allowBreak);
        assertTrue(c.pathing.allowPlace);
        assertEquals(2, c.pathing.searchBudgetMs);
        assertEquals(20_000, c.pathing.maxNodes);
        assertEquals(64, c.pathing.maxGatherBlocks);
        assertTrue(c.pathing.breakBlacklist.contains("#minecraft:planks"));
        assertFalse(c.pathing.breakBlacklist.stream().anyMatch(entry -> entry.contains("terracotta")));
        assertEquals(8, c.version);
        assertEquals(2, c.pathing.toPathingConfig().searchBudgetMs());
    }

    @Test
    void v3FileGetsPathingSection(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("s.json");
        Files.writeString(f, "{\"version\":3,\"chat\":{\"hearingRadius\":8}}");
        var store = new ConfigStore<>(
                f, ServerConfig.class, ServerConfig::defaults, ServerConfig.VERSION, ServerConfig::migrate);
        ServerConfig c = store.load();
        assertEquals(8, c.chat.hearingRadius);
        assertEquals(2, c.pathing.searchBudgetMs);
        assertTrue(c.pathing.allowBreak);
        assertEquals(8, c.version);
        assertTrue(Files.readString(f).contains("\"version\": 8"));
    }

    @Test
    void chunkLoadingDefaultsOnWithOneChunk() {
        var c = ServerConfig.defaults();
        assertTrue(c.companion.chunkLoading.enabled);
        assertEquals(1, c.companion.chunkLoading.radius);
        assertEquals(8, c.version);
    }

    @Test
    void validateClampsChunkLoadingRadius() {
        var c = ServerConfig.defaults();
        c.companion.chunkLoading.radius = 99;
        c.validate();
        assertEquals(4, c.companion.chunkLoading.radius);
        c.companion.chunkLoading.radius = -3;
        c.validate();
        assertEquals(0, c.companion.chunkLoading.radius);
    }

    @Test
    void missingChunkLoadingSectionIsFilled() {
        var c = ServerConfig.defaults();
        c.companion.chunkLoading = null;
        var problems = c.validate();
        assertNotNull(c.companion.chunkLoading);
        assertTrue(c.companion.chunkLoading.enabled);
        assertEquals(1, c.companion.chunkLoading.radius);
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("companion.chunkLoading")));
    }

    @Test
    void pathingRangesClamped() {
        var c = ServerConfig.defaults();
        c.pathing.searchBudgetMs = 50;
        c.pathing.maxNodes = 10;
        c.pathing.maxGatherBlocks = -1;
        c.validate();
        assertEquals(10, c.pathing.searchBudgetMs);
        assertEquals(1000, c.pathing.maxNodes);
        assertEquals(0, c.pathing.maxGatherBlocks);
    }

    @Test
    void treeDefaults() {
        var c = ServerConfig.defaults();
        assertEquals(32, c.tasks.treeSearchRadius);
        assertTrue(c.tasks.fellCollectDrops);
        assertTrue(c.tasks.mineCollectDrops);
        assertTrue(c.tasks.treePerception);
        assertEquals(12, c.tasks.treePerceptionRadius);
        assertEquals(8, c.version);
    }

    @Test
    void treeSettingsAreClamped() {
        var c = ServerConfig.defaults();
        c.tasks.treeSearchRadius = 100;
        c.tasks.treePerceptionRadius = 1;
        List<String> problems = c.validate();
        assertEquals(64, c.tasks.treeSearchRadius);
        assertEquals(4, c.tasks.treePerceptionRadius);
        assertEquals(2, problems.size());
    }

    @Test
    void v5FileGainsTreeDefaults(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("server.json");
        Files.writeString(f, "{\"version\":5,\"tasks\":{\"mineSearchRadius\":20}}");
        var store = new ConfigStore<>(
                f, ServerConfig.class, ServerConfig::defaults, ServerConfig.VERSION, ServerConfig::migrate);
        var config = store.load();
        assertEquals(8, config.version);
        assertEquals(20, config.tasks.mineSearchRadius);
        assertEquals(32, config.tasks.treeSearchRadius);
        assertTrue(config.tasks.treePerception);
    }

    @Test
    void structuresDefaultIsFour() {
        assertEquals(4, ServerConfig.defaults().structures.maxNearbySpawned);
    }

    @Test
    void structuresMaxNearbySpawnedIsClamped() {
        var c = ServerConfig.defaults();
        c.structures.maxNearbySpawned = 0;
        assertEquals(1, clampedMax(c));
        c.structures.maxNearbySpawned = 99;
        assertEquals(16, clampedMax(c));
    }

    private static int clampedMax(ServerConfig c) {
        List<String> problems = c.validate();
        assertEquals(1, problems.size());
        return c.structures.maxNearbySpawned;
    }

    @Test
    void missingStructuresSectionIsFilled() {
        var c = ServerConfig.defaults();
        c.structures = null;
        List<String> problems = c.validate();
        assertEquals(4, c.structures.maxNearbySpawned);
        assertEquals(1, problems.size());
    }

    @Test
    void v6FileGainsStructuresDefault(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("server.json");
        Files.writeString(f, "{\"version\":6,\"tasks\":{\"mineSearchRadius\":20}}");
        var store = new ConfigStore<>(
                f, ServerConfig.class, ServerConfig::defaults, ServerConfig.VERSION, ServerConfig::migrate);
        var config = store.load();
        assertEquals(8, config.version);
        assertEquals(20, config.tasks.mineSearchRadius);
        assertEquals(4, config.structures.maxNearbySpawned);
    }
}
