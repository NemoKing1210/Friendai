package dev.friendai.agent.eval;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Loads the packaged scenario fixtures without assuming resources are expanded into a directory. */
public final class ScenarioResources {
    private static final Gson GSON = new Gson();

    private ScenarioResources() {}

    public static List<Scenario> loadAll() throws IOException {
        var loader = ScenarioResources.class.getClassLoader();
        var index = loader.getResourceAsStream("scenarios/index.txt");
        if (index == null) throw new IOException("Missing scenarios/index.txt");
        try (var reader = new java.io.BufferedReader(new InputStreamReader(index, StandardCharsets.UTF_8))) {
            var scenarios = new java.util.ArrayList<Scenario>();
            for (String name : reader.lines().filter(line -> !line.isBlank()).toList()) {
                var resource = loader.getResourceAsStream("scenarios/" + name);
                if (resource == null) throw new IOException("Missing scenario fixture: " + name);
                try (var scenarioReader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
                    scenarios.add(Objects.requireNonNull(GSON.fromJson(scenarioReader, Scenario.class), name));
                }
            }
            return List.copyOf(scenarios);
        }
    }
}
