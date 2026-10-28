package dev.friendai.build;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A placement refusal is typed, never prose. The build used to grow a new {@code fail(c, "...")} for every new way a
 * block could be blocked; this guard makes the next one impossible without a {@link PlacementRefusal} cause.
 */
class NoUntypedFailureTest {
    private static final Pattern UNTYPED = Pattern.compile("fail\\(c,\\s*\"");
    private static final Path SOURCES = Path.of("src", "main", "java", "dev", "friendai");

    @Test
    void theBuildHasNoUntypedFailureLiteral() throws IOException {
        List<Path> files = new ArrayList<>();
        files.add(SOURCES.resolve("task/builtin/BuildTask.java"));
        try (Stream<Path> build = Files.list(SOURCES.resolve("task/build"))) {
            build.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .forEach(files::add);
        }
        List<String> offenders = new ArrayList<>();
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (UNTYPED.matcher(text).find()) {
                offenders.add(file.toString());
            }
        }
        assertTrue(offenders.isEmpty(), "untyped placement failure in " + offenders);
    }
}
