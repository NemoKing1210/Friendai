package dev.friendai.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.provider.LlmProfile;
import dev.friendai.foundation.diagnostics.ConfigException;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
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
        assertEquals(64, c.tasks.resources.observationRadius);
        assertEquals(32, c.tasks.resources.ownerSightRadius);
        assertEquals(48, c.tasks.resources.ownerHoldDistance);
        assertEquals(24, c.tasks.resources.ownerResumeDistance);
        c.tasks.resources.localRadius = 1;
        c.tasks.resources.expeditionRadius = 1;
        c.tasks.resources.localTicks = 1;
        c.tasks.resources.expeditionTicks = 1;
        c.tasks.resources.observationRadius = 100;
        c.tasks.resources.ownerSightRadius = 200;
        c.tasks.resources.ownerHoldDistance = 1;
        c.tasks.resources.ownerResumeDistance = 500;
        c.validate();
        assertEquals(16, c.tasks.resources.localRadius);
        assertEquals(16, c.tasks.resources.expeditionRadius);
        assertEquals(200, c.tasks.resources.localTicks);
        assertEquals(200, c.tasks.resources.expeditionTicks);
        assertEquals(64, c.tasks.resources.observationRadius);
        assertEquals(128, c.tasks.resources.ownerSightRadius);
        assertTrue(c.tasks.resources.ownerHoldDistance > c.tasks.resources.ownerSightRadius);
        assertTrue(c.tasks.resources.ownerResumeDistance < c.tasks.resources.ownerHoldDistance);
    }

    @Test
    void escortPolicyIsRepairedFromAnInvalidHandEditedConfig() {
        ServerConfig.ResourceSection resources = new ServerConfig.ResourceSection();
        resources.ownerSightRadius = 64;
        resources.ownerHoldDistance = 1;
        resources.ownerResumeDistance = 100;
        var config = resources.escort();
        assertTrue(config.holdDistance() > config.sightRadius());
        assertTrue(config.resumeDistance() < config.holdDistance());
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
        assertEquals(64, c.chat.hearingRadius);
        assertEquals(5000, c.chat.guestCooldownMs);
        assertEquals(ServerConfig.VERSION, c.version);
    }

    @Test
    void memoryDefaults() {
        var c = ServerConfig.defaults();
        assertTrue(c.memory.enabled);
        assertEquals(512, c.memory.maxFactChars);
        assertEquals(128, c.memory.maxFactsPerPlayer);
        assertEquals(128, c.memory.maxWorldFacts);
        assertEquals(ServerConfig.VERSION, c.version);
        assertEquals(512, c.memory.limits().maxFactChars());
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
    void unsupportedOldAndFutureVersionsRemainIntactAndBlockWrites(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("server.json");
        Files.writeString(file, "{\"version\":15,\"activeProfile\":\"legacy\"}");
        ConfigStore<ServerConfig> store =
                new ConfigStore<>(file, ServerConfig.class, ServerConfig::defaults, ServerConfig.VERSION);

        String old = Files.readString(file);
        assertThrows(UnsupportedStoreVersionException.class, store::load);
        assertThrows(UnsupportedStoreVersionException.class, () -> store.save(ServerConfig.defaults()));
        assertEquals(old, Files.readString(file));

        Files.writeString(file, "{\"version\":17,\"activeProfile\":\"future\"}");
        ConfigStore<ServerConfig> futureStore =
                new ConfigStore<>(file, ServerConfig.class, ServerConfig::defaults, ServerConfig.VERSION);
        String future = Files.readString(file);
        assertThrows(UnsupportedStoreVersionException.class, futureStore::load);
        assertThrows(UnsupportedStoreVersionException.class, () -> futureStore.save(ServerConfig.defaults()));
        assertEquals(future, Files.readString(file));
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
        assertEquals(ServerConfig.VERSION, c.version);
        assertEquals(2, c.pathing.toPathingConfig().searchBudgetMs());
    }

    @Test
    void chunkLoadingDefaultsOnWithOneChunk() {
        var c = ServerConfig.defaults();
        assertTrue(c.companion.chunkLoading.enabled);
        assertEquals(1, c.companion.chunkLoading.radius);
        assertEquals(ServerConfig.VERSION, c.version);
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
        assertEquals(ServerConfig.VERSION, c.version);
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
    void agentDefaults() {
        ServerConfig c = ServerConfig.defaults();
        assertTrue(c.agent.toolRouting);
        assertEquals("assistive", c.agent.autonomy);
        assertEquals(3, c.agent.maxReplans);
        assertEquals(30, c.agent.goalTickSec);
        assertEquals(24_000, c.agent.promptBudget);
        assertEquals(60, c.agent.plannerTimeoutSec);
        assertEquals(null, c.agent.planner);
        assertEquals(2, c.agent.plannerRetries);
    }

    @Test
    void agentValuesAreClamped() {
        ServerConfig c = ServerConfig.defaults();
        c.agent.maxReplans = 99;
        c.agent.goalTickSec = 1;
        c.agent.promptBudget = 500;
        c.agent.plannerTimeoutSec = 1;
        c.agent.plannerRetries = 99;
        c.agent.autonomy = "reckless";
        List<String> problems = c.validate();
        assertEquals(10, c.agent.maxReplans);
        assertEquals(10, c.agent.goalTickSec);
        assertEquals(2_000, c.agent.promptBudget);
        assertEquals(10, c.agent.plannerTimeoutSec);
        assertEquals(4, c.agent.plannerRetries);
        assertEquals("assistive", c.agent.autonomy);
        assertTrue(problems.stream().anyMatch(p -> p.contains("agent.autonomy")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("agent.plannerRetries")), problems.toString());
    }

    @Test
    void plannerRetriesClampToOneThroughFour() {
        ServerConfig c = ServerConfig.defaults();
        c.agent.plannerRetries = 0;
        c.validate();
        assertEquals(1, c.agent.plannerRetries);
        c.agent.plannerRetries = 5;
        c.validate();
        assertEquals(4, c.agent.plannerRetries);
    }

    @Test
    void unknownPlannerProfileFallsBackToActiveProfileWithProblem() {
        ServerConfig c = ServerConfig.defaults();
        c.agent.planner = "missing";

        List<String> problems = c.validate();

        assertNull(c.agent.planner);
        assertTrue(problems.stream().anyMatch(p -> p.contains("agent.planner") && p.contains("missing")));
    }

    @Test
    void blankPlannerProfileUsesActiveProfile() {
        ServerConfig c = ServerConfig.defaults();
        c.agent.planner = "  ";

        List<String> problems = c.validate();

        assertNull(c.agent.planner);
        assertFalse(problems.stream().anyMatch(p -> p.contains("agent.planner")));
    }

    @Test
    void zeroPromptBudgetMeansUnlimited() {
        ServerConfig c = ServerConfig.defaults();
        c.agent.promptBudget = 0;
        c.validate();
        assertEquals(0, c.agent.promptBudget);
    }

    @Test
    void missingAgentSectionIsFilled() {
        ServerConfig c = ServerConfig.defaults();
        c.agent = null;
        List<String> problems = c.validate();
        assertNotNull(c.agent);
        assertTrue(problems.contains("agent was missing"), problems.toString());
    }
}
