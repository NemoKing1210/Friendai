package dev.friendai.architecture;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Checks stable module and package boundaries against compiled class dependency edges. */
final class PackageDependencyRules {
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+");
    private static final Set<String> SERVER_STATE_ACCESS_OWNERS = Set.of(
            "dev.friendai.server.runtime.ServerRuntime",
            "dev.friendai.server.runtime.ServerRuntimeFactory",
            "dev.friendai.server.runtime.ServerTickPipeline");
    private static final Set<String> FEATURE_SERVICES_WITH_NARROW_CONTEXT = Set.of(
            "dev.friendai.features.places.minecraft.PlaceService",
            "dev.friendai.features.placements.minecraft.PlacedService");
    private static final Set<FeatureDependency> ALLOWED_FEATURE_DEPENDENCIES = Set.of(
            new FeatureDependency("animals", "combat"),
            new FeatureDependency("animals", "mining"),
            new FeatureDependency("animals", "navigation"),
            new FeatureDependency("enchanting", "mining"),
            new FeatureDependency("enchanting", "navigation"),
            new FeatureDependency("mining", "navigation"),
            new FeatureDependency("mining", "places"),
            new FeatureDependency("mining", "placements"),
            new FeatureDependency("navigation", "mining"),
            new FeatureDependency("navigation", "knowledge"),
            new FeatureDependency("placements", "mining"),
            new FeatureDependency("exploration", "acquisition"),
            new FeatureDependency("exploration", "knowledge"),
            new FeatureDependency("exploration", "mining"),
            new FeatureDependency("exploration", "navigation"),
            new FeatureDependency("fire", "crafting"),
            new FeatureDependency("fire", "mining"),
            new FeatureDependency("fire", "navigation"),
            new FeatureDependency("combat", "navigation"),
            new FeatureDependency("combat", "inventory"),
            new FeatureDependency("combat", "survival"),
            new FeatureDependency("trading", "combat"),
            new FeatureDependency("trading", "navigation"),
            new FeatureDependency("construction", "creative"),
            new FeatureDependency("construction", "fire"),
            new FeatureDependency("construction", "mining"),
            new FeatureDependency("construction", "navigation"),
            new FeatureDependency("construction", "places"),
            new FeatureDependency("construction", "placements"),
            new FeatureDependency("cooking", "crafting"),
            new FeatureDependency("cooking", "mining"),
            new FeatureDependency("cooking", "navigation"),
            new FeatureDependency("acquisition", "crafting"),
            new FeatureDependency("acquisition", "creative"),
            new FeatureDependency("acquisition", "farming"),
            new FeatureDependency("acquisition", "knowledge"),
            new FeatureDependency("acquisition", "navigation"),
            new FeatureDependency("acquisition", "places"),
            new FeatureDependency("acquisition", "trading"),
            new FeatureDependency("knowledge", "acquisition"),
            new FeatureDependency("knowledge", "navigation"),
            new FeatureDependency("crafting", "knowledge"),
            new FeatureDependency("crafting", "mining"),
            new FeatureDependency("crafting", "navigation"),
            new FeatureDependency("farming", "mining"),
            new FeatureDependency("farming", "navigation"),
            new FeatureDependency("farming", "places"),
            new FeatureDependency("farming", "survival"),
            new FeatureDependency("mining", "construction"),
            new FeatureDependency("places", "memory"),
            new FeatureDependency("places", "navigation"),
            new FeatureDependency("placements", "navigation"),
            new FeatureDependency("instructions", "memory"),
            new FeatureDependency("interaction", "creative"),
            new FeatureDependency("interaction", "combat"),
            new FeatureDependency("interaction", "navigation"),
            new FeatureDependency("inventory", "acquisition"),
            new FeatureDependency("inventory", "navigation"),
            new FeatureDependency("inventory", "placements"),
            new FeatureDependency("inventory", "places"),
            new FeatureDependency("inventory", "survival"),
            new FeatureDependency("survival", "combat"),
            new FeatureDependency("survival", "cooking"),
            new FeatureDependency("survival", "farming"),
            new FeatureDependency("survival", "knowledge"),
            new FeatureDependency("survival", "mining"),
            new FeatureDependency("survival", "navigation"));
    private static final Set<String> FEATURE_TOOL_REGISTRATION_API = Set.of(
            "dev.friendai.server.execution.tools.FeatureToolContributor",
            "dev.friendai.server.execution.tools.FeatureToolRegistry",
            "dev.friendai.server.execution.tools.TaskToolDefinition",
            "dev.friendai.server.execution.tools.TaskToolDefinition$Factory",
            "dev.friendai.server.execution.tools.TaskToolDefinition$CallerFactory",
            "dev.friendai.server.execution.tools.TaskToolDefinition$QueueTraits");
    /** Immutable records carried by menu views; feature policies and repositories remain outside presentation. */
    private static final Set<String> PRESENTATION_FEATURE_VIEW_TYPES = Set.of(
            "dev.friendai.features.instructions.InstructionSkill",
            "dev.friendai.features.instructions.SkillView",
            "dev.friendai.features.memory.Category",
            "dev.friendai.features.memory.CompanionDescription",
            "dev.friendai.features.memory.MemoryView",
            "dev.friendai.features.memory.MemoryView$FactRow",
            "dev.friendai.features.memory.MemoryView$Group",
            "dev.friendai.features.memory.MenuStatus",
            "dev.friendai.features.memory.OwnerProfile",
            "dev.friendai.features.places.PlaceView",
            "dev.friendai.features.places.PlaceView$Row",
            "dev.friendai.features.trading.TradeView");

    private static final List<String> PURE_PACKAGES = List.of(
            "dev.friendai.agent",
            "dev.friendai.infrastructure.config",
            "dev.friendai.execution.queue",
            "dev.friendai.execution.tool",
            "dev.friendai.foundation",
            "dev.friendai.infrastructure",
            "dev.friendai.llm",
            "dev.friendai.features.speech");
    private static final Comparator<Violation> VIOLATION_ORDER = Comparator.comparing(Violation::ruleId)
            .thenComparing(Violation::sourceClass)
            .thenComparing(Violation::targetClass);

    private PackageDependencyRules() {}

    static List<DependencyEdge> parseJdepsOutput(String output) {
        if (output == null || output.isBlank()) {
            throw new IllegalArgumentException("jdeps returned no output");
        }

        Set<DependencyEdge> edges = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            if (!line.contains("->") || line.isBlank() || !Character.isWhitespace(line.charAt(0))) {
                continue;
            }
            String[] sides = line.trim().split("\\s+->\\s+", 2);
            if (sides.length != 2) {
                throw new IllegalArgumentException("Malformed jdeps class edge: " + line);
            }
            String sourceClass = sides[0].trim();
            String[] targetAndModule = sides[1].trim().split("\\s+", 2);
            if (targetAndModule.length == 0
                    || !CLASS_NAME.matcher(sourceClass).matches()
                    || !CLASS_NAME.matcher(targetAndModule[0]).matches()) {
                throw new IllegalArgumentException("Malformed jdeps class edge: " + line);
            }
            edges.add(new DependencyEdge(sourceClass, targetAndModule[0]));
        }
        if (edges.isEmpty()) {
            throw new IllegalArgumentException("jdeps output contained no class dependency edges");
        }
        return edges.stream()
                .sorted(Comparator.comparing(DependencyEdge::sourceClass).thenComparing(DependencyEdge::targetClass))
                .toList();
    }

    static List<Violation> validate(List<DependencyEdge> edges) {
        Set<Violation> violations = new LinkedHashSet<>();
        for (DependencyEdge edge : edges) {
            String source = edge.sourceClass();
            String target = edge.targetClass();
            if (isUnder(source, "dev.friendai.foundation") && isFoundationForbidden(target)) {
                add(violations, "foundation-direction", edge);
            }
            if (isPurePackage(source) && isPlatformPackage(target)) {
                add(violations, "pure-java-no-platform", edge);
            }
            if (isUnder(source, "dev.friendai.features") && isFeatureAdapter(source, target)) {
                add(violations, "feature-to-server", edge);
            }
            if (hasUnlistedFeatureDependency(source, target)) {
                add(violations, "feature-dependency-not-allowed", edge);
            }
            if (isUnder(source, "dev.friendai.features")
                    && isUnder(target, "dev.friendai.features")
                    && isMinecraftFeatureAdapter(source)
                    && !featureBoundary(source).equals(featureBoundary(target))
                    && isFeatureTaskImplementation(target)) {
                add(violations, "feature-task-internals-not-allowed", edge);
            }
            if (isUnder(source, "dev.friendai.agent")
                    && !isUnder(source, "dev.friendai.agent.llm.protocol")
                    && isAgentAdapter(target)) {
                add(violations, "agent-direction", edge);
            }
            if (isUnder(source, "dev.friendai.server") && isUnder(target, "dev.friendai.client")) {
                add(violations, "server-to-client", edge);
            }
            if (isOptionalUiPackage(target) && !isUnder(source, "dev.friendai.client.config")) {
                add(violations, "optional-integration-isolation", edge);
            }
            if (isUnder(source, "dev.friendai.presentation")
                    && isUnder(target, "dev.friendai.features")
                    && !isFeatureView(target)) {
                add(violations, "presentation-feature-view", edge);
            }
            if (isUnder(source, "dev.friendai.presentation") && isPresentationAdapter(target)) {
                add(violations, "presentation-to-adapter", edge);
            }
            if ((isUnder(source, "dev.friendai.protocol") || isUnder(source, "dev.friendai.agent.llm.protocol"))
                    && isProtocolForbidden(target)) {
                add(violations, "protocol-direction", edge);
            }
            // Runtime construction and tick orchestration own transitional state access.
            if (target.equals("dev.friendai.core.FriendaiServerState")
                    && !SERVER_STATE_ACCESS_OWNERS.contains(source)) {
                add(violations, "legacy-server-state-lookup", edge);
            }
            if (FEATURE_SERVICES_WITH_NARROW_CONTEXT.contains(source)
                    && target.equals("dev.friendai.task.TaskContext")) {
                add(violations, "feature-task-context-leak", edge);
            }
        }
        return violations.stream().sorted(VIOLATION_ORDER).toList();
    }

    static List<Violation> validateEngineDependencies(List<DependencyEdge> edges) {
        Set<Violation> violations = new LinkedHashSet<>();
        for (DependencyEdge edge : edges) {
            if (isEngineForbiddenDependency(edge.targetClass())) {
                add(violations, "engine-forbidden-dependency", edge);
            }
        }
        return violations.stream().sorted(VIOLATION_ORDER).toList();
    }

    private static void add(Set<Violation> violations, String ruleId, DependencyEdge edge) {
        violations.add(new Violation(ruleId, edge.sourceClass(), edge.targetClass()));
    }

    private static boolean isPurePackage(String className) {
        return PURE_PACKAGES.stream().anyMatch(prefix -> isUnder(className, prefix));
    }

    private static boolean isPlatformPackage(String className) {
        return isUnder(className, "net.minecraft") || isUnder(className, "net.fabricmc");
    }

    private static boolean isEngineForbiddenDependency(String className) {
        return isPlatformPackage(className)
                || isOptionalUiPackage(className)
                || isUnder(className, "io.github.givimad.whisperjni")
                || isUnder(className, "com.k2fsa.sherpa.onnx");
    }

    private static boolean isFoundationForbidden(String className) {
        return isPlatformPackage(className)
                || isUnder(className, "dev.friendai.agent")
                || isUnder(className, "dev.friendai.features")
                || isUnder(className, "dev.friendai.infrastructure")
                || isUnder(className, "dev.friendai.presentation")
                || isUnder(className, "dev.friendai.protocol")
                || isUnder(className, "dev.friendai.server")
                || isUnder(className, "dev.friendai.client")
                || isUnder(className, "dev.friendai.transport");
    }

    private static boolean isFeatureAdapter(String source, String target) {
        return isUnder(target, "dev.friendai.server") && !isAllowedFeatureToolApiDependency(source, target)
                || isUnder(target, "dev.friendai.client")
                || isUnder(target, "dev.friendai.transport")
                || isUnder(target, "dev.friendai.infrastructure")
                || isUnder(target, "dev.friendai.presentation")
                || !isMinecraftFeatureAdapter(source) && isPlatformPackage(target);
    }

    private static boolean isAllowedFeatureToolApiDependency(String source, String target) {
        if (!FEATURE_TOOL_REGISTRATION_API.contains(target) || !isUnder(source, "dev.friendai.features")) {
            return false;
        }
        String[] segments = source.substring("dev.friendai.features.".length()).split("\\.");
        return segments.length >= 4 && segments[1].equals("minecraft") && segments[2].equals("tool");
    }

    private static boolean hasUnlistedFeatureDependency(String source, String target) {
        if (!isUnder(source, "dev.friendai.features") || !isUnder(target, "dev.friendai.features")) {
            return false;
        }
        String sourceFeature = featureBoundary(source);
        String targetFeature = featureBoundary(target);
        return !sourceFeature.equals(targetFeature)
                && !ALLOWED_FEATURE_DEPENDENCIES.contains(new FeatureDependency(sourceFeature, targetFeature));
    }

    private static String featureBoundary(String className) {
        String featureClass = className.substring("dev.friendai.features.".length());
        int nextPackage = featureClass.indexOf('.');
        return nextPackage < 0 ? featureClass : featureClass.substring(0, nextPackage);
    }

    private static boolean isFeatureTaskImplementation(String className) {
        return isUnder(className, "dev.friendai.features") && className.contains(".task.");
    }

    private static boolean isMinecraftFeatureAdapter(String className) {
        if (!isUnder(className, "dev.friendai.features")) {
            return false;
        }
        String[] segments =
                className.substring("dev.friendai.features.".length()).split("\\.");
        return segments.length >= 3 && segments[1].equals("minecraft");
    }

    private static boolean isAgentAdapter(String className) {
        return isUnder(className, "dev.friendai.server")
                || isUnder(className, "dev.friendai.client")
                || isUnder(className, "dev.friendai.transport")
                || isUnder(className, "dev.friendai.infrastructure")
                || isUnder(className, "dev.friendai.presentation")
                || isPlatformPackage(className);
    }

    private static boolean isPresentationAdapter(String className) {
        return isUnder(className, "dev.friendai.agent")
                || isUnder(className, "dev.friendai.server")
                || isUnder(className, "dev.friendai.client")
                || isUnder(className, "dev.friendai.transport")
                || isUnder(className, "dev.friendai.infrastructure")
                || isPlatformPackage(className);
    }

    private static boolean isProtocolForbidden(String className) {
        return isUnder(className, "dev.friendai.presentation")
                || isUnder(className, "dev.friendai.infrastructure")
                || isUnder(className, "dev.friendai.server")
                || isUnder(className, "dev.friendai.client")
                || isUnder(className, "dev.friendai.transport")
                || isPlatformPackage(className);
    }

    private static boolean isOptionalUiPackage(String className) {
        return isUnder(className, "dev.isxander.yacl3") || isUnder(className, "com.terraformersmc.modmenu");
    }

    private static boolean isFeatureView(String className) {
        if (!isUnder(className, "dev.friendai.features")) {
            return false;
        }
        if (PRESENTATION_FEATURE_VIEW_TYPES.contains(className)) {
            return true;
        }
        String[] segments =
                className.substring("dev.friendai.features.".length()).split("\\.");
        for (int index = 1; index < segments.length - 1; index++) {
            if (segments[index].equals("view")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnder(String className, String packageName) {
        return className.equals(packageName) || className.startsWith(packageName + ".");
    }

    record DependencyEdge(String sourceClass, String targetClass) {
        DependencyEdge {
            if (sourceClass == null || sourceClass.isBlank() || targetClass == null || targetClass.isBlank()) {
                throw new IllegalArgumentException("Dependency edge class names must be non-empty");
            }
        }
    }

    private record FeatureDependency(String sourceFeature, String targetFeature) {}

    record Violation(String ruleId, String sourceClass, String targetClass) {
        Violation {
            if (ruleId == null
                    || ruleId.isBlank()
                    || sourceClass == null
                    || sourceClass.isBlank()
                    || targetClass == null
                    || targetClass.isBlank()) {
                throw new IllegalArgumentException("Dependency violation fields must be non-empty");
            }
        }
    }
}
