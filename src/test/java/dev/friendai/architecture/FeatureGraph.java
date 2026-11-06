package dev.friendai.architecture;

import static dev.friendai.architecture.PackageDependencyRules.DependencyEdge;
import static dev.friendai.architecture.PackageDependencyRules.Violation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Feature-level dependency graph used to keep the feature packages acyclic. */
final class FeatureGraph {
    static final String CYCLE_RULE = "feature-graph-acyclic";
    private static final String FEATURE_PREFIX = "dev.friendai.features.";

    private FeatureGraph() {}

    /** Returns the feature boundary of a class, or {@code null} when the class is not under features. */
    static String featureOf(String className) {
        if (className == null || !className.startsWith(FEATURE_PREFIX)) {
            return null;
        }
        String remainder = className.substring(FEATURE_PREFIX.length());
        int nextPackage = remainder.indexOf('.');
        return nextPackage < 0 ? remainder : remainder.substring(0, nextPackage);
    }

    /**
     * Returns the strongly connected components that contain more than one feature, each sorted and the list
     * ordered by its smallest member.
     */
    static List<Set<String>> cycles(List<DependencyEdge> edges) {
        Map<String, Set<String>> graph = featureGraph(edges);
        List<Set<String>> components = stronglyConnectedComponents(graph).stream()
                .filter(component -> component.size() > 1)
                .map(TreeSet::new)
                .map(component -> (Set<String>) component)
                .sorted((left, right) ->
                        left.iterator().next().compareTo(right.iterator().next()))
                .toList();
        return components;
    }

    /** Returns one violation per current cycle that no baseline set covers completely. */
    static List<Violation> violations(List<DependencyEdge> edges, List<Set<String>> baseline) {
        List<Violation> violations = new ArrayList<>();
        for (Set<String> cycle : cycles(edges)) {
            boolean covered = baseline.stream().anyMatch(known -> known.containsAll(cycle));
            if (!covered) {
                Violation first = firstEdgeInside(edges, cycle);
                if (first != null) {
                    violations.add(first);
                }
            }
        }
        return violations;
    }

    private static Map<String, Set<String>> featureGraph(List<DependencyEdge> edges) {
        Map<String, Set<String>> graph = new TreeMap<>();
        for (DependencyEdge edge : edges) {
            String source = featureOf(edge.sourceClass());
            String target = featureOf(edge.targetClass());
            if (source == null || target == null || source.equals(target)) {
                continue;
            }
            graph.computeIfAbsent(source, ignored -> new TreeSet<>()).add(target);
            graph.computeIfAbsent(target, ignored -> new TreeSet<>());
        }
        return graph;
    }

    private static Violation firstEdgeInside(List<DependencyEdge> edges, Set<String> cycle) {
        for (DependencyEdge edge : edges) {
            String source = featureOf(edge.sourceClass());
            String target = featureOf(edge.targetClass());
            if (source != null
                    && target != null
                    && !source.equals(target)
                    && cycle.contains(source)
                    && cycle.contains(target)) {
                return new Violation(CYCLE_RULE, edge.sourceClass(), edge.targetClass());
            }
        }
        return null;
    }

    private static List<Set<String>> stronglyConnectedComponents(Map<String, Set<String>> graph) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> lowLink = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new LinkedHashSet<>();
        List<Set<String>> components = new ArrayList<>();
        int[] nextIndex = {0};

        for (String node : graph.keySet()) {
            if (!index.containsKey(node)) {
                visit(node, graph, index, lowLink, stack, onStack, components, nextIndex);
            }
        }
        return components;
    }

    private static void visit(
            String node,
            Map<String, Set<String>> graph,
            Map<String, Integer> index,
            Map<String, Integer> lowLink,
            Deque<String> stack,
            Set<String> onStack,
            List<Set<String>> components,
            int[] nextIndex) {
        index.put(node, nextIndex[0]);
        lowLink.put(node, nextIndex[0]);
        nextIndex[0]++;
        stack.push(node);
        onStack.add(node);

        for (String target : graph.getOrDefault(node, Set.of())) {
            if (!index.containsKey(target)) {
                visit(target, graph, index, lowLink, stack, onStack, components, nextIndex);
                lowLink.put(node, Math.min(lowLink.get(node), lowLink.get(target)));
            } else if (onStack.contains(target)) {
                lowLink.put(node, Math.min(lowLink.get(node), index.get(target)));
            }
        }

        if (lowLink.get(node).equals(index.get(node))) {
            Set<String> component = new LinkedHashSet<>();
            String member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(node));
            components.add(component);
        }
    }
}
