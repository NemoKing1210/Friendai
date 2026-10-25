package dev.friendai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.queue.QueuedStep;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QueueParserTest {
    private static final Map<String, JsonObject> SCHEMAS = Map.of("go_to", SchemaBuilder.object().build());
    @Test void parsesStepsAndRejectsForwardDependenciesAtomically() {
        JsonArray good = JsonParser.parseString("[{\"tool\":\"go_to\",\"args\":\"{}\"}]").getAsJsonArray();
        assertEquals(1, QueueParser.parse(good, SCHEMAS, Set.of("go_to"), 32, 8192, 49152).steps().size());
        JsonArray bad = JsonParser.parseString("[{\"tool\":\"go_to\",\"after\":[1]}]").getAsJsonArray();
        var result = QueueParser.parse(bad, SCHEMAS, Set.of("go_to"), 32, 8192, 49152);
        assertTrue(result.steps().isEmpty());
        assertTrue(result.errors().getFirst().contains("earlier step"));
    }
}
