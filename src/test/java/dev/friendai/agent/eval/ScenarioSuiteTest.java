package dev.friendai.agent.eval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Runs every {@code scenarios/*.json} resource against the brain with scripted fakes. */
class ScenarioSuiteTest {
    @TestFactory
    Stream<DynamicTest> allScenariosPass() throws IOException, URISyntaxException {
        Path dir = Path.of(ScenarioSuiteTest.class.getResource("/scenarios").toURI());
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertTrue(!files.isEmpty(), "no scenarios found in " + dir);
        Gson gson = new Gson();
        return files.stream()
                .map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
                    Scenario scenario = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Scenario.class);
                    ScenarioRunner.Result result = ScenarioRunner.run(scenario);
                    assertTrue(result.passed(), scenario.name() + ": " + result.failures());
                }));
    }
}
