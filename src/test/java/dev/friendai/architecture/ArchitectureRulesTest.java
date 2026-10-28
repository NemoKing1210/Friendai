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
    void forbiddenPackageDirectionsReportExactClassesAndAllowedViewsPass() {
        List<DependencyEdge> edges = List.of(
                edge("dev.friendai.foundation.Text", "net.minecraft.world.level.Level"),
                edge("dev.friendai.text.LanguageDetector", "net.fabricmc.api.ModInitializer"),
                edge("dev.friendai.agent.turn.Agent", "dev.friendai.infrastructure.llm.HttpTransport"),
                edge("dev.friendai.features.navigation.Navigator", "dev.friendai.server.ServerRuntime"),
                edge("dev.friendai.features.navigation.Navigator", "net.minecraft.server.MinecraftServer"),
                edge(
                        "dev.friendai.features.trading.minecraft.TraderRefreshService",
                        "net.minecraft.server.MinecraftServer"),
                edge(
                        "dev.friendai.features.trading.minecraft.TraderRefreshService",
                        "dev.friendai.server.runtime.ServerRuntime"),
                edge("dev.friendai.server.LegacyCaller", "dev.friendai.core.FriendaiServerState"),
                edge("dev.friendai.server.runtime.ServerTickPipeline", "dev.friendai.core.FriendaiServerState"),
                edge("dev.friendai.server.runtime.ServerRuntime", "dev.friendai.client.ui.CompanionScreen"),
                edge("dev.friendai.server.runtime.ServerRuntime", "dev.isxander.yacl3.api.YetAnotherConfigLib"),
                edge(
                        "dev.friendai.presentation.companion.CompanionModel",
                        "dev.friendai.features.companion.CompanionBrain"),
                edge("dev.friendai.presentation.companion.CompanionModel", "dev.friendai.server.runtime.ServerRuntime"),
                edge(
                        "dev.friendai.protocol.companion.CompanionPayload",
                        "dev.friendai.presentation.companion.CompanionModel"),
                edge("dev.friendai.client.config.SettingsScreen", "dev.isxander.yacl3.api.YetAnotherConfigLib"),
                edge(
                        "dev.friendai.presentation.companion.CompanionModel",
                        "dev.friendai.features.companion.view.CompanionView"));

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
                                "feature-to-server",
                                "dev.friendai.features.trading.minecraft.TraderRefreshService",
                                "dev.friendai.server.runtime.ServerRuntime"),
                        violation(
                                "foundation-direction",
                                "dev.friendai.foundation.Text",
                                "net.minecraft.world.level.Level"),
                        violation(
                                "legacy-server-state-lookup",
                                "dev.friendai.server.LegacyCaller",
                                "dev.friendai.core.FriendaiServerState"),
                        violation(
                                "optional-integration-isolation",
                                "dev.friendai.server.runtime.ServerRuntime",
                                "dev.isxander.yacl3.api.YetAnotherConfigLib"),
                        violation(
                                "presentation-feature-view",
                                "dev.friendai.presentation.companion.CompanionModel",
                                "dev.friendai.features.companion.CompanionBrain"),
                        violation(
                                "presentation-to-adapter",
                                "dev.friendai.presentation.companion.CompanionModel",
                                "dev.friendai.server.runtime.ServerRuntime"),
                        violation(
                                "protocol-direction",
                                "dev.friendai.protocol.companion.CompanionPayload",
                                "dev.friendai.presentation.companion.CompanionModel"),
                        violation(
                                "pure-java-no-platform",
                                "dev.friendai.text.LanguageDetector",
                                "net.fabricmc.api.ModInitializer"),
                        violation(
                                "server-to-client",
                                "dev.friendai.server.runtime.ServerRuntime",
                                "dev.friendai.client.ui.CompanionScreen")),
                PackageDependencyRules.validate(edges));
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
        assertFalse(documented.isEmpty(), "Legacy dependencies must remain visible with migration owners");
        Set<Integer> removalTasks = Set.of(4, 5, 8, 9, 11, 14, 16, 17);
        assertTrue(
                documented.stream().allMatch(edge -> removalTasks.contains(edge.taskId())),
                "Every legacy dependency must name its removal task");

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
                90, gameTestClasses.size(), "Update this inventory count when GameTest scope intentionally changes");

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
