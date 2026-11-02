package dev.friendai.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConfigInteractionStyleTest {
    @TempDir
    Path dir;

    @Test
    void defaultsToBalanced() {
        assertEquals("balanced", ServerConfig.defaults().companion.interactionStyle);
    }

    @Test
    void aHandEditedStyleIsNormalisedOrFallsBackToBalanced() {
        ServerConfig c = ServerConfig.defaults();
        c.companion.interactionStyle = "EXPRESSIVE ";
        assertTrue(c.validate().isEmpty());
        assertEquals("expressive", c.companion.interactionStyle);

        c.companion.interactionStyle = "chatty";
        List<String> problems = c.validate();
        assertEquals("balanced", c.companion.interactionStyle);
        assertTrue(
                problems.stream().anyMatch(problem -> problem.contains("companion.interactionStyle")),
                problems.toString());

        c.companion.interactionStyle = null;
        problems = c.validate();
        assertEquals("balanced", c.companion.interactionStyle);
        assertTrue(
                problems.stream().anyMatch(problem -> problem.contains("companion.interactionStyle")),
                problems.toString());
    }

    @Test
    void theChosenStyleSurvivesASaveAndLoad() {
        ConfigStore<ServerConfig> store = ConfigStores.server(dir.resolve("friendai-server.json"));
        ServerConfig config = store.load();
        config.companion.interactionStyle = "expressive";
        store.save(config);
        ServerConfig again = store.load();
        assertEquals("expressive", again.companion.interactionStyle);
        assertEquals(ServerConfig.VERSION, again.version);
    }

    @Test
    void anUnknownStoredValueLoadsAsBalanced() throws IOException {
        Path path = dir.resolve("unknown-style.json");
        Files.writeString(
                path, "{\"version\":" + ServerConfig.VERSION + ",\"companion\":{\"interactionStyle\":\"chatty\"}}");
        assertEquals("balanced", ConfigStores.server(path).load().companion.interactionStyle);
    }
}
