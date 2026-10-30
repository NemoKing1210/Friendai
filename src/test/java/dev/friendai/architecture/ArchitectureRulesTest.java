package dev.friendai.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.architecture.PackageDependencyRules.DependencyEdge;
import dev.friendai.architecture.PackageDependencyRules.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ArchitectureRulesTest {
    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Pattern GAME_TEST_ANNOTATION = Pattern.compile("(?m)^\\s*@GameTest(?:Generator)?\\b");

    @Test
    void parsesClassEdgesAndKeepsMissingExternalTargets() {
        String output = String.join(
                "\n",
                "dev.friendai -> java.base",
                "   dev.friendai.features.navigation.Navigator -> dev.friendai.server.ServerRuntime main",
                "   dev.friendai.features.navigation.Navigator -> com.vendor.MissingApi not found");

        assertEquals(
                Set.of(
                        edge("dev.friendai.features.navigation.Navigator", "dev.friendai.server.ServerRuntime"),
                        edge("dev.friendai.features.navigation.Navigator", "com.vendor.MissingApi")),
                new HashSet<>(PackageDependencyRules.parseJdepsOutput(output)));
    }

    @Test
    void malformedClassEdgeOutputFailsLoudly() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PackageDependencyRules.parseJdepsOutput("   dev.friendai.Foo ->"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PackageDependencyRules.parseJdepsOutput("jdeps returned no class dependency edges"));
    }

    @Test
    void gameChatAdaptersBelongToTheServerAgentLayer() {
        Path target = PROJECT_ROOT.resolve("src/main/java/dev/friendai/server/agent/chat");
        Path legacy = PROJECT_ROOT.resolve("src/main/java/dev/friendai/chat");

        for (String adapter : List.of("ChatRouter.java", "CompanionRouting.java", "CompanionSpeaker.java")) {
            assertTrue(
                    Files.exists(target.resolve(adapter)),
                    "Server chat adapter should live under server/agent/chat: " + adapter);
            assertFalse(Files.exists(legacy.resolve(adapter)), "Legacy chat adapter should be removed: " + adapter);
        }
    }

    @Test
    void perceptionModelsAreEngineOwnedAndWorldBuildersAreServerAdapters() {
        Path models = PROJECT_ROOT.resolve("engine/src/main/java/dev/friendai/agent/perception");
        Path adapters = PROJECT_ROOT.resolve("src/main/java/dev/friendai/server/agent/perception");
        Path legacy = PROJECT_ROOT.resolve("src/main/java/dev/friendai/perception");

        for (String model : List.of("AreaMap.java", "OwnerFocus.java", "PerceptionSnapshot.java", "SelfStatus.java")) {
            assertTrue(Files.exists(models.resolve(model)), "Pure perception model should live in engine: " + model);
            assertFalse(Files.exists(legacy.resolve(model)), "Legacy perception model should be removed: " + model);
        }
        for (String adapter : List.of(
                "AreaMapBuilder.java",
                "GroundText.java",
                "OwnerFocusBuilder.java",
                "PerceptionBuilder.java",
                "PerceptionContext.java")) {
            assertTrue(
                    Files.exists(adapters.resolve(adapter)),
                    "World perception builder should be server-owned: " + adapter);
            assertFalse(
                    Files.exists(legacy.resolve(adapter)), "Legacy perception builder should be removed: " + adapter);
        }
        assertTrue(Files.exists(
                PROJECT_ROOT.resolve("engine/src/main/java/dev/friendai/features/knowledge/Landmark.java")));
        assertTrue(Files.exists(
                PROJECT_ROOT.resolve("engine/src/main/java/dev/friendai/features/navigation/RelativeDirection.java")));
        assertTrue(Files.exists(PROJECT_ROOT.resolve("engine/src/main/java/dev/friendai/features/combat/Threat.java")));
        assertTrue(Files.exists(
                PROJECT_ROOT.resolve("engine/src/main/java/dev/friendai/features/mining/tree/TreeSummary.java")));
        assertTrue(Files.exists(
                PROJECT_ROOT.resolve("engine/src/main/java/dev/friendai/features/mining/tree/TreeText.java")));
        assertTrue(Files.exists(
                PROJECT_ROOT.resolve("src/main/java/dev/friendai/features/knowledge/minecraft/LandmarkScanner.java")));
    }

    @Test
    void commandAdaptersDoNotLookUpTheRuntimeServiceGraph() throws IOException {
        Path commandRoot = PROJECT_ROOT.resolve("src/main/java/dev/friendai/command");
        try (Stream<Path> files = Files.walk(commandRoot)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                assertFalse(
                        Files.readString(file).contains("ServerServiceGraph"),
                        "Command adapters should use runtime operations instead of the legacy state: " + file);
            }
        }
    }

    @Test
    void chatAdaptersDoNotLookUpTheRuntimeServiceGraph() throws IOException {
        Path chatRoot = PROJECT_ROOT.resolve("src/main/java/dev/friendai/server/agent/chat");
        for (String adapter : List.of("ChatRouter.java", "CompanionRouting.java")) {
            assertFalse(
                    Files.readString(chatRoot.resolve(adapter)).contains("ServerServiceGraph"),
                    "Chat adapters should use the runtime supplied at their boundary: " + adapter);
        }
    }

    @Test
    void miningAndSurvivalTasksUseTaskContextServices() throws IOException {
        for (String task : List.of(
                "features/mining/minecraft/task/FellTreeSearchTask.java",
                "features/mining/minecraft/task/MineBlocksTask.java",
                "features/survival/minecraft/task/GetFoodTask.java",
                "features/survival/minecraft/task/SleepTask.java")) {
            Path source = PROJECT_ROOT.resolve("src/main/java/dev/friendai").resolve(task);
            assertFalse(
                    Files.readString(source).contains("ServerServiceGraph"),
                    "Feature tasks should receive runtime services through TaskContext: " + task);
        }
    }

    @Test
    void legacyCoreAdaptersHaveNamedOwners() {
        Path root = PROJECT_ROOT.resolve("src/main/java/dev/friendai");
        assertFalse(Files.exists(root.resolve("core")), "the legacy core package must not return");
        for (String owned : List.of(
                "server/creative/CreativeModeService.java",
                "server/instructions/SkillService.java",
                "server/memory/TrustConfirmations.java",
                "server/agent/OutcomeFacts.java",
                "common/Components.java")) {
            assertTrue(Files.isRegularFile(root.resolve(owned)), "adapter should have a named owner: " + owned);
        }
    }

    @Test
    void placementServiceLivesWithItsFeature() {
        assertTrue(Files.exists(
                PROJECT_ROOT.resolve("src/main/java/dev/friendai/features/placements/minecraft/PlacedService.java")));
        assertFalse(Files.exists(PROJECT_ROOT.resolve("src/main/java/dev/friendai/placed/PlacedService.java")));
    }

    @Test
    void forbiddenPackageDirectionsReportExactClassesAndAllowedViewsPass() {
        List<DependencyEdge> edges = List.of(
                edge("dev.friendai.foundation.Text", "net.minecraft.world.level.Level"),
                edge("dev.friendai.foundation.text.LanguageDetector", "net.fabricmc.api.ModInitializer"),
                edge("dev.friendai.agent.turn.Agent", "dev.friendai.infrastructure.llm.HttpTransport"),
                edge("dev.friendai.features.navigation.Navigator", "dev.friendai.server.ServerRuntime"),
                edge("dev.friendai.features.navigation.Navigator", "net.minecraft.server.MinecraftServer"),
                edge("dev.friendai.server.LegacyCaller", "dev.friendai.server.runtime.ServerServiceGraph"),
                edge("dev.friendai.task.TaskContext", "dev.friendai.server.runtime.ServerServiceGraph"),
                edge(
                        "dev.friendai.server.runtime.ServerTickPipeline",
                        "dev.friendai.server.runtime.ServerServiceGraph"),
                edge("dev.friendai.server.runtime.ServerRuntime", "dev.friendai.client.ui.CompanionScreen"),
                edge("dev.friendai.server.runtime.ServerRuntime", "dev.isxander.yacl3.api.YetAnotherConfigLib"),
                edge(
                        "dev.friendai.presentation.companion.CompanionModel",
                        "dev.friendai.features.companion.CompanionBrain"),
                edge("dev.friendai.presentation.companion.CompanionModel", "dev.friendai.server.runtime.ServerRuntime"),
                edge(
                        "dev.friendai.protocol.companion.CompanionPayload",
                        "dev.friendai.presentation.companion.CompanionModel"),
                edge(
                        "dev.friendai.agent.llm.protocol.OpenAiChatProtocol",
                        "dev.friendai.infrastructure.llm.HttpTransport"),
                edge("dev.friendai.client.config.SettingsScreen", "dev.isxander.yacl3.api.YetAnotherConfigLib"),
                edge(
                        "dev.friendai.presentation.companion.CompanionModel",
                        "dev.friendai.features.companion.view.CompanionView"),
                edge(
                        "dev.friendai.presentation.companion.CompanionViewState",
                        "dev.friendai.features.instructions.SkillView"),
                edge(
                        "dev.friendai.presentation.companion.CompanionViewState",
                        "dev.friendai.features.instructions.InstructionSkill"),
                edge(
                        "dev.friendai.presentation.companion.CompanionViewState",
                        "dev.friendai.features.memory.CompanionDescription"),
                edge(
                        "dev.friendai.presentation.companion.CompanionViewState",
                        "dev.friendai.features.memory.OwnerProfile"),
                edge(
                        "dev.friendai.presentation.companion.CompanionViewState",
                        "dev.friendai.features.memory.MemoryView"),
                edge(
                        "dev.friendai.presentation.companion.CompanionViewState",
                        "dev.friendai.features.places.PlaceView"),
                edge(
                        "dev.friendai.presentation.companion.CompanionViewState",
                        "dev.friendai.features.trading.TradeView"),
                edge("dev.friendai.presentation.companion.MemoryTabModel", "dev.friendai.features.memory.Category"),
                edge("dev.friendai.presentation.companion.MemoryTabModel", "dev.friendai.features.memory.MenuStatus"),
                edge(
                        "dev.friendai.presentation.companion.MemoryTabModel",
                        "dev.friendai.features.memory.MemoryView$FactRow"),
                edge(
                        "dev.friendai.presentation.companion.MemoryTabModel",
                        "dev.friendai.features.memory.MemoryView$Group"),
                edge("dev.friendai.presentation.companion.PlacesTabModel", "dev.friendai.features.memory.MenuStatus"),
                edge(
                        "dev.friendai.presentation.companion.PlacesTabModel",
                        "dev.friendai.features.places.PlaceView$Row"),
                edge(
                        "dev.friendai.presentation.companion.CompanionViewState",
                        "dev.friendai.features.memory.MemoryPolicy"));

        assertEquals(
                List.of(
                        violation(
                                "agent-direction",
                                "dev.friendai.agent.turn.Agent",
                                "dev.friendai.infrastructure.llm.HttpTransport"),
                        violation(
                                "feature-to-server",
                                "dev.friendai.features.navigation.Navigator",
                                "dev.friendai.server.ServerRuntime"),
                        violation(
                                "feature-to-server",
                                "dev.friendai.features.navigation.Navigator",
                                "net.minecraft.server.MinecraftServer"),
                        violation(
                                "foundation-direction",
                                "dev.friendai.foundation.Text",
                                "net.minecraft.world.level.Level"),
                        violation(
                                "foundation-direction",
                                "dev.friendai.foundation.text.LanguageDetector",
                                "net.fabricmc.api.ModInitializer"),
                        violation(
                                "optional-integration-isolation",
                                "dev.friendai.server.runtime.ServerRuntime",
                                "dev.isxander.yacl3.api.YetAnotherConfigLib"),
                        violation(
                                "presentation-feature-view",
                                "dev.friendai.presentation.companion.CompanionModel",
                                "dev.friendai.features.companion.CompanionBrain"),
                        violation(
                                "presentation-feature-view",
                                "dev.friendai.presentation.companion.CompanionViewState",
                                "dev.friendai.features.memory.MemoryPolicy"),
                        violation(
                                "presentation-to-adapter",
                                "dev.friendai.presentation.companion.CompanionModel",
                                "dev.friendai.server.runtime.ServerRuntime"),
                        violation(
                                "protocol-direction",
                                "dev.friendai.agent.llm.protocol.OpenAiChatProtocol",
                                "dev.friendai.infrastructure.llm.HttpTransport"),
                        violation(
                                "protocol-direction",
                                "dev.friendai.protocol.companion.CompanionPayload",
                                "dev.friendai.presentation.companion.CompanionModel"),
                        violation(
                                "pure-java-no-platform",
                                "dev.friendai.foundation.Text",
                                "net.minecraft.world.level.Level"),
                        violation(
                                "pure-java-no-platform",
                                "dev.friendai.foundation.text.LanguageDetector",
                                "net.fabricmc.api.ModInitializer"),
                        violation(
                                "runtime-service-graph-boundary",
                                "dev.friendai.server.LegacyCaller",
                                "dev.friendai.server.runtime.ServerServiceGraph"),
                        violation(
                                "runtime-service-graph-boundary",
                                "dev.friendai.task.TaskContext",
                                "dev.friendai.server.runtime.ServerServiceGraph"),
                        violation(
                                "server-to-client",
                                "dev.friendai.server.runtime.ServerRuntime",
                                "dev.friendai.client.ui.CompanionScreen")),
                PackageDependencyRules.validate(edges));
    }

    @Test
    void featureDependenciesRequireAnExplicitDirectedEdge() {
        List<DependencyEdge> edges = new ArrayList<>(List.of(
                edge(
                        "dev.friendai.features.mining.ExplorationPlanner",
                        "dev.friendai.features.navigation.pathing.PathStep"),
                edge("dev.friendai.features.fire.FirePlanner", "dev.friendai.features.navigation.pathing.Pos"),
                edge("dev.friendai.features.construction.BuildOrder", "dev.friendai.features.navigation.pathing.Pos"),
                edge("dev.friendai.features.acquisition.ObtainPlanner", "dev.friendai.features.crafting.CraftPlan"),
                edge("dev.friendai.features.acquisition.ObtainPlanner", "dev.friendai.features.navigation.pathing.Pos"),
                edge("dev.friendai.features.acquisition.ObtainPlanner", "dev.friendai.features.trading.TradePlan"),
                edge(
                        "dev.friendai.features.knowledge.ResourceKnowledge",
                        "dev.friendai.features.navigation.pathing.Pos"),
                edge(
                        "dev.friendai.features.knowledge.ResourceKnowledge",
                        "dev.friendai.features.acquisition.OreDepths"),
                edge("dev.friendai.features.places.Place", "dev.friendai.features.navigation.pathing.Pos"),
                edge("dev.friendai.features.places.Place", "dev.friendai.features.memory.MenuStatus"),
                edge("dev.friendai.features.placements.PlacedBlock", "dev.friendai.features.navigation.pathing.Pos"),
                edge(
                        "dev.friendai.features.placements.minecraft.tool.PlacedTools",
                        "dev.friendai.features.mining.minecraft.mining.BlockMatcher"),
                edge("dev.friendai.features.instructions.SkillView", "dev.friendai.features.memory.MenuStatus"),
                edge(
                        "dev.friendai.features.interaction.minecraft.task.ReachEntityTask",
                        "dev.friendai.features.navigation.minecraft.nav.EntityApproach"),
                edge(
                        "dev.friendai.features.interaction.minecraft.task.ExecuteCommandTask",
                        "dev.friendai.features.creative.CreativeCommandPolicy"),
                edge(
                        "dev.friendai.features.inventory.minecraft.task.GiveItemsTask",
                        "dev.friendai.features.navigation.minecraft.nav.MoveHelper"),
                edge(
                        "dev.friendai.features.inventory.minecraft.task.DepositTask",
                        "dev.friendai.features.acquisition.ToolNeed"),
                edge(
                        "dev.friendai.features.inventory.minecraft.task.DepositTask",
                        "dev.friendai.features.places.PlaceQuery"),
                edge(
                        "dev.friendai.features.mining.minecraft.mining.MiningRefusal",
                        "dev.friendai.features.places.Place"),
                edge(
                        "dev.friendai.features.navigation.minecraft.nav.PathExecutor",
                        "dev.friendai.features.mining.minecraft.mining.BreakAction"),
                edge(
                        "dev.friendai.features.acquisition.minecraft.task.ObtainTask",
                        "dev.friendai.features.creative.minecraft.CreativeItemCapability"),
                edge(
                        "dev.friendai.features.inventory.minecraft.tool.InventoryTools",
                        "dev.friendai.features.survival.HungerModel"),
                edge(
                        "dev.friendai.features.animals.minecraft.Animals",
                        "dev.friendai.features.combat.minecraft.Threats"),
                edge(
                        "dev.friendai.features.animals.minecraft.task.LeadTask",
                        "dev.friendai.features.navigation.minecraft.nav.Approach"),
                edge(
                        "dev.friendai.features.animals.minecraft.task.HoneyTask",
                        "dev.friendai.features.mining.minecraft.mining.BlockMatcher"),
                edge(
                        "dev.friendai.features.combat.minecraft.CombatController",
                        "dev.friendai.features.navigation.movement.Facing"),
                edge(
                        "dev.friendai.features.combat.minecraft.CombatController",
                        "dev.friendai.features.inventory.minecraft.Equipment"),
                edge(
                        "dev.friendai.features.combat.minecraft.CombatController",
                        "dev.friendai.features.survival.HungerModel"),
                edge(
                        "dev.friendai.features.survival.minecraft.task.EatTask",
                        "dev.friendai.features.combat.minecraft.CombatController"),
                edge(
                        "dev.friendai.features.survival.minecraft.task.GetFoodTask",
                        "dev.friendai.features.cooking.minecraft.FuelPolicy"),
                edge(
                        "dev.friendai.features.survival.minecraft.task.GetFoodTask",
                        "dev.friendai.features.farming.minecraft.Crops"),
                edge(
                        "dev.friendai.features.survival.minecraft.task.GetFoodTask",
                        "dev.friendai.features.knowledge.ResourceObservation"),
                edge(
                        "dev.friendai.features.survival.minecraft.task.GetFoodTask",
                        "dev.friendai.features.navigation.pathing.Pos"),
                edge(
                        "dev.friendai.features.survival.minecraft.task.SleepTask",
                        "dev.friendai.features.mining.minecraft.mining.BlockMatcher"),
                edge(
                        "dev.friendai.features.survival.minecraft.task.SleepTask",
                        "dev.friendai.features.navigation.minecraft.nav.Approach"),
                edge(
                        "dev.friendai.features.construction.minecraft.task.ClearAreaTask",
                        "dev.friendai.features.fire.minecraft.FireHands"),
                edge(
                        "dev.friendai.features.interaction.minecraft.task.InteractEntityTask",
                        "dev.friendai.features.combat.minecraft.Threats"),
                edge("dev.friendai.features.fire.minecraft.FireScan", "dev.friendai.features.crafting.Station"),
                edge(
                        "dev.friendai.features.fire.minecraft.task.ExtinguishFireTask",
                        "dev.friendai.features.mining.minecraft.mining.BlockBreaker"),
                edge(
                        "dev.friendai.features.fire.minecraft.FireScan",
                        "dev.friendai.features.navigation.minecraft.nav.LevelCellView"),
                edge(
                        "dev.friendai.features.navigation.minecraft.tool.RelativeMovementTools",
                        "dev.friendai.features.interaction.minecraft.task.ApproachTask"),
                edge(
                        "dev.friendai.features.creative.CreativeCommandPolicy",
                        "dev.friendai.features.interaction.minecraft.task.ApproachTask"),
                edge("dev.friendai.features.combat.CombatPolicy", "dev.friendai.features.farming.FarmPlanner"),
                edge("dev.friendai.features.navigation.Navigator", "dev.friendai.features.acquisition.ObtainPlan")));

        assertEquals(
                List.of(
                        violation(
                                "feature-dependency-not-allowed",
                                "dev.friendai.features.combat.CombatPolicy",
                                "dev.friendai.features.farming.FarmPlanner"),
                        violation(
                                "feature-dependency-not-allowed",
                                "dev.friendai.features.creative.CreativeCommandPolicy",
                                "dev.friendai.features.interaction.minecraft.task.ApproachTask"),
                        violation(
                                "feature-dependency-not-allowed",
                                "dev.friendai.features.navigation.Navigator",
                                "dev.friendai.features.acquisition.ObtainPlan"),
                        violation(
                                "feature-dependency-not-allowed",
                                "dev.friendai.features.navigation.minecraft.tool.RelativeMovementTools",
                                "dev.friendai.features.interaction.minecraft.task.ApproachTask"),
                        violation(
                                "feature-task-internals-not-allowed",
                                "dev.friendai.features.navigation.minecraft.tool.RelativeMovementTools",
                                "dev.friendai.features.interaction.minecraft.task.ApproachTask")),
                PackageDependencyRules.validate(edges));
    }

    @Test
    void featureAdaptersCannotConstructAnotherFeaturesTasks() {
        String obtainTask = "dev.friendai.features.acquisition.minecraft.task.ObtainTask";
        String creativeTask = "dev.friendai.features.creative.minecraft.task.CreateItemsTask";

        assertEquals(
                List.of(violation("feature-task-internals-not-allowed", obtainTask, creativeTask)),
                PackageDependencyRules.validate(List.of(edge(obtainTask, creativeTask))));
    }

    @Test
    void gameplayTasksAreCoLocatedWithTheirFeatures() {
        for (String[] task : List.of(
                new String[] {"combat", "AttackPlayerTask"},
                new String[] {"combat", "HuntTask"},
                new String[] {"survival", "EatTask"},
                new String[] {"survival", "GetFoodTask"},
                new String[] {"survival", "SleepTask"},
                new String[] {"animals", "BreedTask"},
                new String[] {"animals", "FishTask"},
                new String[] {"animals", "HoneyTask"},
                new String[] {"animals", "LeadTask"},
                new String[] {"animals", "MilkTask"},
                new String[] {"animals", "ShearTask"},
                new String[] {"animals", "TameTask"},
                new String[] {"trading", "BarterTask"},
                new String[] {"trading", "InspectTraderTask"},
                new String[] {"trading", "LevelTraderTask"},
                new String[] {"trading", "RefreshTradesTask"},
                new String[] {"trading", "TradeTask"},
                new String[] {"trading", "WaitForRestockTask"},
                new String[] {"enchanting", "AnvilItemTask"},
                new String[] {"enchanting", "EnchantItemTask"},
                new String[] {"enchanting", "GrindstoneItemTask"},
                new String[] {"enchanting", "MenuBlockTask"},
                new String[] {"fire", "ExtinguishFireTask"})) {
            Path featureTask = PROJECT_ROOT.resolve(
                    "src/main/java/dev/friendai/features/" + task[0] + "/minecraft/task/" + task[1] + ".java");
            Path legacyTask = PROJECT_ROOT.resolve("src/main/java/dev/friendai/task/builtin/" + task[1] + ".java");

            assertTrue(Files.isRegularFile(featureTask), "Task should be feature-owned: " + featureTask);
            assertFalse(Files.exists(legacyTask), "Legacy task should be removed: " + legacyTask);
        }
    }

    @Test
    void featureToolAdaptersMayUseRegistrationApiButNotServerRuntimeServices() {
        String toolAdapter = "dev.friendai.features.navigation.minecraft.tool.MovementTools";
        List<DependencyEdge> allowed = List.of(
                edge(toolAdapter, "dev.friendai.server.execution.tools.FeatureToolContributor"),
                edge(toolAdapter, "dev.friendai.server.execution.tools.FeatureToolRegistry"),
                edge(toolAdapter, "dev.friendai.server.execution.tools.TaskToolDefinition"),
                edge(toolAdapter, "dev.friendai.server.execution.tools.TaskToolDefinition$Factory"),
                edge(toolAdapter, "dev.friendai.server.execution.tools.TaskToolDefinition$CallerFactory"),
                edge(toolAdapter, "dev.friendai.server.execution.tools.TaskToolDefinition$QueueTraits"));
        assertTrue(PackageDependencyRules.validate(allowed).isEmpty());

        assertEquals(
                List.of(
                        violation(
                                "feature-to-server",
                                "dev.friendai.features.navigation.minecraft.nav.Navigator",
                                "dev.friendai.server.execution.tools.FeatureToolRegistry"),
                        violation("feature-to-server", toolAdapter, "dev.friendai.server.runtime.ServerRuntime")),
                PackageDependencyRules.validate(List.of(
                        edge(toolAdapter, "dev.friendai.server.runtime.ServerRuntime"),
                        edge(
                                "dev.friendai.features.navigation.minecraft.nav.Navigator",
                                "dev.friendai.server.execution.tools.FeatureToolRegistry"))));
    }

    @Test
    void featureToolAdaptersCannotLookUpRuntimeServiceGraph() {
        String gatheringTools = "dev.friendai.features.mining.minecraft.tool.GatheringTools";

        assertEquals(
                List.of(
                        violation(
                                "feature-to-server", gatheringTools, "dev.friendai.server.runtime.ServerServiceGraph"),
                        violation(
                                "runtime-service-graph-boundary",
                                gatheringTools,
                                "dev.friendai.server.runtime.ServerServiceGraph")),
                PackageDependencyRules.validate(
                        List.of(edge(gatheringTools, "dev.friendai.server.runtime.ServerServiceGraph"))));
    }

    @Test
    void featureAdaptersReceiveNarrowServicesInsteadOfCreatingTaskContexts() {
        String placeService = "dev.friendai.features.places.minecraft.PlaceService";
        String placedService = "dev.friendai.features.placements.minecraft.PlacedService";

        assertEquals(
                List.of(
                        violation("feature-task-context-leak", placedService, "dev.friendai.task.TaskContext"),
                        violation("feature-task-context-leak", placeService, "dev.friendai.task.TaskContext")),
                PackageDependencyRules.validate(List.of(
                        edge(placeService, "dev.friendai.task.TaskContext"),
                        edge(placedService, "dev.friendai.task.TaskContext"))));
    }

    @Test
    void engineRejectsGameOptionalUiAndNativeAudioDependencies() {
        List<DependencyEdge> edges = List.of(
                edge("dev.friendai.foundation.Text", "net.minecraft.world.level.Level"),
                edge("dev.friendai.foundation.Text", "net.fabricmc.api.ModInitializer"),
                edge("dev.friendai.foundation.Settings", "dev.isxander.yacl3.api.YetAnotherConfigLib"),
                edge("dev.friendai.foundation.Settings", "com.terraformersmc.modmenu.api.ConfigScreenFactory"),
                edge("dev.friendai.features.speech.Stt", "io.github.givimad.whisperjni.WhisperJNI"),
                edge("dev.friendai.features.speech.Tts", "com.k2fsa.sherpa.onnx.OfflineTts"));

        assertEquals(
                Set.of(
                        violation(
                                "engine-forbidden-dependency",
                                "dev.friendai.foundation.Text",
                                "net.minecraft.world.level.Level"),
                        violation(
                                "engine-forbidden-dependency",
                                "dev.friendai.foundation.Text",
                                "net.fabricmc.api.ModInitializer"),
                        violation(
                                "engine-forbidden-dependency",
                                "dev.friendai.foundation.Settings",
                                "dev.isxander.yacl3.api.YetAnotherConfigLib"),
                        violation(
                                "engine-forbidden-dependency",
                                "dev.friendai.foundation.Settings",
                                "com.terraformersmc.modmenu.api.ConfigScreenFactory"),
                        violation(
                                "engine-forbidden-dependency",
                                "dev.friendai.features.speech.Stt",
                                "io.github.givimad.whisperjni.WhisperJNI"),
                        violation(
                                "engine-forbidden-dependency",
                                "dev.friendai.features.speech.Tts",
                                "com.k2fsa.sherpa.onnx.OfflineTts")),
                Set.copyOf(PackageDependencyRules.validateEngineDependencies(edges)));
    }

    @Test
    void failedJdepsInvocationDoesNotBecomeAnEmptyGraph() {
        assertThrows(
                IOException.class,
                () -> ArchitectureFixtures.runJdeps(Path.of("missing-jdeps-executable"), PROJECT_ROOT, ""));
    }

    @Test
    void compiledProjectEdgesMatchOnlyDocumentedClassLevelExceptions() throws Exception {
        String classpath = System.getProperty("friendai.architecture.classpath", "");
        assertFalse(classpath.isBlank(), "Gradle must provide compiled project dependencies to jdeps");

        List<DependencyEdge> edges = ArchitectureFixtures.collectCompiledProjectEdges(PROJECT_ROOT, classpath);
        assertFalse(edges.isEmpty(), "jdeps must return compiled project class edges");
        List<DependencyEdge> engineEdges = ArchitectureFixtures.collectEngineEdges(PROJECT_ROOT, classpath);
        assertTrue(
                PackageDependencyRules.validateEngineDependencies(engineEdges).isEmpty(),
                "Engine class dependencies must exclude game, optional UI and native audio packages");
        List<Violation> actual = PackageDependencyRules.validate(edges);
        List<ArchitectureFixtures.LegacyEdge> documented =
                ArchitectureFixtures.readLegacyEdges(PROJECT_ROOT.resolve("architecture/legacy-dependency-edges.tsv"));
        assertTrue(documented.isEmpty(), "No transitional class dependency exceptions are permitted");

        Set<Violation> expected = documented.stream()
                .map(edge -> violation(edge.ruleId(), edge.sourceClass(), edge.targetClass()))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(expected, Set.copyOf(actual), "Unexpected or stale class dependency exceptions");
    }

    @Test
    void modAndGameTestEntrypointsResolveAndRetainTheCurrentTestScope() throws IOException {
        JsonObject modMetadata = readJson(PROJECT_ROOT.resolve("src/main/resources/fabric.mod.json"));
        JsonObject entrypoints = modMetadata.getAsJsonObject("entrypoints");
        Set<String> registeredModEntrypoints = new HashSet<>();
        for (String group : entrypoints.keySet()) {
            for (var entrypoint : entrypoints.getAsJsonArray(group)) {
                String className = entrypoint.getAsString();
                assertTrue(registeredModEntrypoints.add(className), "Duplicate mod entrypoint: " + className);
                Path sourceRoot = className.startsWith("dev.friendai.client.")
                        ? PROJECT_ROOT.resolve("src/client/java")
                        : PROJECT_ROOT.resolve("src/main/java");
                assertTrue(sourceFor(sourceRoot, className).toFile().isFile(), "Missing mod entrypoint: " + className);
            }
        }

        JsonObject gameTestMetadata = readJson(PROJECT_ROOT.resolve("src/gametest/resources/fabric.mod.json"));
        JsonArray registeredGameTests =
                gameTestMetadata.getAsJsonObject("entrypoints").getAsJsonArray("fabric-gametest");
        Set<String> gameTestClasses = new HashSet<>();
        for (var entrypoint : registeredGameTests) {
            String className = entrypoint.getAsString();
            assertTrue(gameTestClasses.add(className), "Duplicate GameTest entrypoint: " + className);
            assertTrue(
                    sourceFor(PROJECT_ROOT.resolve("src/gametest/java"), className)
                            .toFile()
                            .isFile(),
                    "Missing GameTest entrypoint: " + className);
        }
        assertEquals(
                93, gameTestClasses.size(), "Update this inventory count when GameTest scope intentionally changes");

        try (Stream<Path> files = Files.walk(PROJECT_ROOT.resolve("src/gametest/java"))) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (GAME_TEST_ANNOTATION.matcher(source).find()) {
                    String className = classNameFromPath(PROJECT_ROOT.resolve("src/gametest/java"), file);
                    assertTrue(gameTestClasses.contains(className), "Unregistered GameTest class: " + className);
                }
            }
        }
    }

    @Test
    void everyProductionResourceFromRootAndEngineIsLoadable() throws IOException {
        List<Path> resourceRoots =
                List.of(PROJECT_ROOT.resolve("src/main/resources"), PROJECT_ROOT.resolve("engine/src/main/resources"));
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (Path resourceRoot : resourceRoots) {
            if (!Files.isDirectory(resourceRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(resourceRoot)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String resourceName =
                            resourceRoot.relativize(file).toString().replace('\\', '/');
                    assertTrue(
                            loader.getResource(resourceName) != null,
                            "Resource is absent from production classpath: " + resourceName);
                }
            }
        }
    }

    @Test
    void productionToolContributorsDeclareTheirOwnCatalogMetadata() throws IOException {
        List<Path> sourceRoots = List.of(
                PROJECT_ROOT.resolve("src/main/java/dev/friendai/features"),
                PROJECT_ROOT.resolve("src/main/java/dev/friendai/server/agent/tools"),
                PROJECT_ROOT.resolve("src/main/java/dev/friendai/server/execution/tools"));
        for (Path sourceRoot : sourceRoots) {
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                for (Path file : files.filter(
                                path -> path.getFileName().toString().endsWith(".java"))
                        .toList()) {
                    assertFalse(
                            Files.readString(file).contains("ToolCatalog.defaults()"),
                            "Tool metadata must be declared by its contributor, not looked up centrally: " + file);
                }
            }
        }
    }

    private static JsonObject readJson(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    private static Path sourceFor(Path sourceRoot, String className) {
        return sourceRoot.resolve(className.replace('.', '/') + ".java");
    }

    private static String classNameFromPath(Path sourceRoot, Path file) {
        String relative = sourceRoot.relativize(file).toString().replace('\\', '/');
        return relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
    }

    private static DependencyEdge edge(String sourceClass, String targetClass) {
        return new DependencyEdge(sourceClass, targetClass);
    }

    private static Violation violation(String ruleId, String sourceClass, String targetClass) {
        return new Violation(ruleId, sourceClass, targetClass);
    }
}
