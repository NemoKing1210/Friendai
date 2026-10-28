package dev.friendai.architecture;

import dev.friendai.architecture.PackageDependencyRules.DependencyEdge;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runs the JDK dependency tool and reads the explicit migration inventory used by architecture tests. */
final class ArchitectureFixtures {
    private static final List<String> PROJECT_OUTPUTS =
            List.of("build/classes/java/main", "build/classes/java/client", "engine/build/classes/java/main");

    private ArchitectureFixtures() {}

    static List<DependencyEdge> collectCompiledProjectEdges(Path projectRoot, String classpath)
            throws IOException, InterruptedException {
        String executableName = System.getProperty("os.name", "").toLowerCase().contains("win") ? "jdeps.exe" : "jdeps";
        Path executable = Path.of(System.getProperty("java.home"), "bin", executableName);
        return runJdeps(executable, projectRoot, classpath);
    }

    static List<DependencyEdge> collectEngineEdges(Path projectRoot, String classpath)
            throws IOException, InterruptedException {
        String executableName = System.getProperty("os.name", "").toLowerCase().contains("win") ? "jdeps.exe" : "jdeps";
        Path executable = Path.of(System.getProperty("java.home"), "bin", executableName);
        return runJdeps(executable, projectRoot, classpath, List.of("engine/build/classes/java/main"));
    }

    static List<DependencyEdge> runJdeps(Path executable, Path projectRoot, String classpath)
            throws IOException, InterruptedException {
        return runJdeps(executable, projectRoot, classpath, PROJECT_OUTPUTS);
    }

    private static List<DependencyEdge> runJdeps(
            Path executable, Path projectRoot, String classpath, List<String> outputPaths)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(
                List.of(executable.toString(), "--multi-release", "25", "-verbose:class", "-filter:none"));
        if (classpath != null && !classpath.isBlank()) {
            command.add("--class-path");
            command.add(classpath);
        }
        for (String output : outputPaths) {
            Path classes = projectRoot.resolve(output);
            if (!Files.isDirectory(classes)) {
                throw new IOException("Compiled project classes are missing: " + classes);
            }
            command.add(classes.toString());
        }

        Process process = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("jdeps failed with exit code " + exitCode + ":\n" + output);
        }
        try {
            return PackageDependencyRules.parseJdepsOutput(output);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Could not parse jdeps output", exception);
        }
    }

    static List<LegacyEdge> readLegacyEdges(Path manifest) throws IOException {
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("Legacy dependency manifest is missing: " + manifest);
        }
        List<LegacyEdge> result = new ArrayList<>();
        Set<DependencyEdge> uniqueEdges = new HashSet<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] columns = line.split("\\t", -1);
            if (columns.length != 4) {
                throw new IOException("Malformed legacy edge at " + manifest + ":" + lineNumber);
            }
            int taskId;
            try {
                taskId = Integer.parseInt(columns[3]);
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid migration task id at " + manifest + ":" + lineNumber, exception);
            }
            LegacyEdge edge = new LegacyEdge(columns[0], columns[1], columns[2], taskId);
            if (!uniqueEdges.add(new DependencyEdge(edge.sourceClass(), edge.targetClass()))) {
                throw new IOException("Duplicate legacy dependency edge at " + manifest + ":" + lineNumber);
            }
            result.add(edge);
        }
        return List.copyOf(result);
    }

    record LegacyEdge(String ruleId, String sourceClass, String targetClass, int taskId) {
        LegacyEdge {
            if (ruleId == null
                    || ruleId.isBlank()
                    || sourceClass == null
                    || sourceClass.isBlank()
                    || targetClass == null
                    || targetClass.isBlank()
                    || taskId < 1) {
                throw new IllegalArgumentException("Legacy dependency entries require a rule, class edge and task id");
            }
        }
    }
}
