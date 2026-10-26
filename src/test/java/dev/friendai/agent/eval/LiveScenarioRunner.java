package dev.friendai.agent.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.friendai.brain.LlmGateway;
import dev.friendai.config.ServerConfig;
import dev.friendai.llm.LlmClient;
import dev.friendai.llm.http.HttpTransport;
import dev.friendai.llm.provider.ProviderRegistry;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Runs the scenarios against a real provider and writes {@code build/eval/<timestamp>.md}. Opt in with
 * {@code -Dfriendai.live=true -Dfriendai.live.config=<path to friendai-server.json>}. The tools are the test fake,
 * so the report measures requests per scenario and what the model says, not real world actions.
 */
class LiveScenarioRunner {
    @Test
    void liveRunnerIsSkippedByDefault() {
        assertFalse(Boolean.getBoolean("friendai.live"), "the live runner must not be enabled in a normal build");
    }

    @Test
    @EnabledIfSystemProperty(named = "friendai.live", matches = "true")
    void runAgainstTheConfiguredProvider() throws IOException, URISyntaxException {
        String configPath = System.getProperty("friendai.live.config");
        assertTrue(configPath != null && !configPath.isBlank(), "pass -Dfriendai.live.config=<server config>");
        Gson gson = new Gson();
        ServerConfig config = gson.fromJson(Files.readString(Path.of(configPath), StandardCharsets.UTF_8), ServerConfig.class);
        HttpTransport transport = new HttpTransport(
                HttpClient.newHttpClient(), Duration.ofSeconds(config.brain.requestTimeoutSec), 2, Duration.ofMillis(500));
        LlmGateway live = new LlmClient(ProviderRegistry.withBuiltins(transport), config.toLlmProfile(System::getenv));

        Path dir = Path.of(LiveScenarioRunner.class.getResource("/scenarios").toURI());
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        List<String> report = new ArrayList<>();
        report.add("# Live evaluation " + LocalDateTime.now());
        report.add("");
        report.add("| scenario | requests | tools | spoken | result |");
        report.add("|---|---|---|---|---|");
        for (Path file : files) {
            Scenario scenario = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Scenario.class);
            ScenarioRunner.Result result = ScenarioRunner.run(scenario, live);
            report.add("| " + scenario.name() + " | " + result.requests() + " | " + result.toolsCalled()
                    + " | " + String.join(" / ", result.spoken()).replace("|", "/") + " | "
                    + (result.passed() ? "pass" : "FAIL " + result.failures()) + " |");
        }
        Path out = Path.of("build/eval");
        Files.createDirectories(out);
        Files.write(out.resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".md"), report);
    }
}
