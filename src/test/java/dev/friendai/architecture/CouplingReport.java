package dev.friendai.architecture;

import static dev.friendai.architecture.PackageDependencyRules.DependencyEdge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Writes the generated coupling inventory used to curate {@code architecture/coupling-baseline.md}. */
final class CouplingReport {
    private static final Path REPORT = Path.of("build/architecture-report.md");

    private CouplingReport() {}

    static void write(Path projectRoot, List<DependencyEdge> edges, List<Set<String>> cycles) throws IOException {
        Map<String, Set<String>> classFanOut = new TreeMap<>();
        Map<String, Map<String, Integer>> featureEdges = new TreeMap<>();
        int featureEdgeCount = 0;

        for (DependencyEdge edge : edges) {
            classFanOut
                    .computeIfAbsent(edge.sourceClass(), ignored -> new TreeSet<>())
                    .add(edge.targetClass());
            String source = FeatureGraph.featureOf(edge.sourceClass());
            String target = FeatureGraph.featureOf(edge.targetClass());
            if (source == null || target == null || source.equals(target)) {
                continue;
            }
            featureEdgeCount++;
            featureEdges.computeIfAbsent(source, ignored -> new TreeMap<>()).merge(target, 1, Integer::sum);
        }

        StringBuilder out = new StringBuilder();
        out.append("# Architecture coupling report\n\n");
        out.append("Generated from `")
                .append(edges.size())
                .append("` compiled class edges (")
                .append(featureEdgeCount)
                .append(" feature-to-feature).\n\n");

        out.append("## Strongly connected components\n\n");
        if (cycles.isEmpty()) {
            out.append("None.\n\n");
        } else {
            for (Set<String> cycle : cycles) {
                out.append("- ").append(String.join(", ", cycle)).append('\n');
            }
            out.append('\n');
        }

        out.append("## Feature edges\n\n");
        out.append("| source | target | class edges |\n|---|---|---:|\n");
        for (var sourceEntry : featureEdges.entrySet()) {
            for (var targetEntry : sourceEntry.getValue().entrySet()) {
                out.append("| ")
                        .append(sourceEntry.getKey())
                        .append(" | ")
                        .append(targetEntry.getKey())
                        .append(" | ")
                        .append(targetEntry.getValue())
                        .append(" |\n");
            }
        }
        out.append('\n');

        out.append("## Bidirectional feature pairs\n\n");
        Set<String> pairs = new TreeSet<>();
        for (var sourceEntry : featureEdges.entrySet()) {
            for (String target : sourceEntry.getValue().keySet()) {
                if (featureEdges.containsKey(target) && featureEdges.get(target).containsKey(sourceEntry.getKey())) {
                    String first = sourceEntry.getKey().compareTo(target) < 0 ? sourceEntry.getKey() : target;
                    String second = sourceEntry.getKey().compareTo(target) < 0 ? target : sourceEntry.getKey();
                    pairs.add(first + " <-> " + second);
                }
            }
        }
        if (pairs.isEmpty()) {
            out.append("None.\n\n");
        } else {
            for (String pair : pairs) {
                out.append("- ").append(pair).append('\n');
            }
            out.append('\n');
        }

        out.append("## Top class fan-out\n\n");
        out.append("| class | distinct outgoing classes |\n|---|---:|\n");
        List<Map.Entry<String, Set<String>>> ranked = new ArrayList<>(classFanOut.entrySet());
        ranked.sort(Comparator.<Map.Entry<String, Set<String>>>comparingInt(
                        entry -> entry.getValue().size())
                .reversed()
                .thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, Set<String>> entry : ranked.stream().limit(25).toList()) {
            out.append("| `")
                    .append(entry.getKey())
                    .append("` | ")
                    .append(entry.getValue().size())
                    .append(" |\n");
        }

        Path report = projectRoot.resolve(REPORT);
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
    }
}
