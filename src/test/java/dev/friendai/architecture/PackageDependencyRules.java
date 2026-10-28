package dev.friendai.architecture;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Checks stable module and package boundaries against compiled class dependency edges. */
final class PackageDependencyRules {
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+");
    private static final Set<String> SERVER_RUNTIME_STATE_OWNERS = Set.of(
            "dev.friendai.server.runtime.ServerRuntime",
            "dev.friendai.server.runtime.ServerRuntimeFactory",
            "dev.friendai.server.runtime.ServerTickPipeline");
    private static final List<String> PURE_PACKAGES = List.of(
            "dev.friendai.agent",
            "dev.friendai.config",
            "dev.friendai.execution.queue",
            "dev.friendai.execution.tool",
            "dev.friendai.foundation",
            "dev.friendai.llm",
            "dev.friendai.voice");
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
            // Runtime construction and tick orchestration intentionally own the transitional state graph.
            if (target.equals("dev.friendai.core.FriendaiServerState")
                    && !SERVER_RUNTIME_STATE_OWNERS.contains(source)) {
                add(violations, "legacy-server-state-lookup", edge);
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
        if (isUnder(className, "dev.friendai.tool.builtin")) {
            return false;
        }
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
        return isUnder(target, "dev.friendai.server")
                || isUnder(target, "dev.friendai.client")
                || isUnder(target, "dev.friendai.transport")
                || isUnder(target, "dev.friendai.infrastructure")
                || isUnder(target, "dev.friendai.presentation")
                || !isMinecraftFeatureAdapter(source) && isPlatformPackage(target);
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
