package dev.friendai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.queue.PlanNode;
import dev.friendai.queue.PlanDefinition;
import dev.friendai.queue.Guard;
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
    @Test void rejectsTooManyDependenciesAndBadAttemptCount() {
        String afters = "[1,1,1,1,1,1,1,1,1]";
        JsonArray many = JsonParser.parseString("[{\"tool\":\"go_to\"},{\"tool\":\"go_to\",\"after\":" + afters + "}]").getAsJsonArray();
        var result = QueueParser.parse(many, SCHEMAS, Set.of("go_to"), 32, 8192, 49152);
        assertTrue(result.errors().getFirst().contains("at most"));
        JsonArray attempts = JsonParser.parseString("[{\"tool\":\"go_to\",\"max_attempts\":9}]").getAsJsonArray();
        assertTrue(!QueueParser.parse(attempts, SCHEMAS, Set.of("go_to"), 32, 8192, 49152).ok());
    }

    @Test
    void parsesConditionalAndCappedRepeatNodesWithinWorstCaseBudget() {
        JsonArray input = JsonParser.parseString("""
                [
                  {"when":{"type":"any_of","conditions":[
                    {"type":"biome","biome":"minecraft:plains"},
                    {"type":"fluid_ahead"}
                  ]},"then":[{"tool":"go_to","args":"{}"}],"else":[]},
                  {"repeat":{"steps":[{"tool":"go_to","args":"{}"}],
                    "until":{"type":"time_elapsed","ticks":100},"max_iterations":4}}
                ]
                """).getAsJsonArray();

        var parsed = QueueParser.parsePlan(input, SCHEMAS, Set.of("go_to"), 5, 8192, 49152);

        assertTrue(parsed.ok(), parsed.errors().toString());
        assertTrue(parsed.plan().nodes().get(0) instanceof PlanNode.Conditional);
        assertTrue(parsed.plan().nodes().get(1) instanceof PlanNode.Repeat);
        assertEquals(5, parsed.plan().worstCaseActions());
    }

    @Test
    void reportsEveryNestedErrorWithItsPathAndRejectsExcessExpansionAndCharacters() {
        JsonArray invalid = JsonParser.parseString("""
                [
                  {"when":{"type":"any_of","conditions":[
                    {"type":"unknown"}, {"type":"dimension"}
                  ]},"then":[{"tool":"missing_tool"}]},
                  {"repeat":{"steps":[{"tool":"go_to"}],
                    "until":{"type":"time_elapsed","ticks":1},"max_iterations":65}}
                ]
                """).getAsJsonArray();

        var rejected = QueueParser.parsePlan(invalid, SCHEMAS, Set.of("go_to"), 32, 8192, 49152);

        assertFalse(rejected.ok());
        assertTrue(rejected.plan() == null);
        assertTrue(rejected.errors().stream().anyMatch(e -> e.contains("step 1 → if → condition → any_of #1")));
        assertTrue(rejected.errors().stream().anyMatch(e -> e.contains("step 1 → if → condition → any_of #2")));
        assertTrue(rejected.errors().stream().anyMatch(e -> e.contains("step 1 → then #1")));
        assertTrue(rejected.errors().stream().anyMatch(e -> e.contains("step 2 → repeat")));

        JsonObject nested = new JsonObject();
        nested.addProperty("tool", "go_to");
        for (int i = 0; i < 7; i++) {
            JsonObject repeat = new JsonObject();
            JsonArray body = new JsonArray();
            body.add(nested);
            repeat.add("steps", body);
            repeat.add("until", JsonParser.parseString("{\"type\":\"time_elapsed\",\"ticks\":1}"));
            repeat.addProperty("max_iterations", 64);
            JsonObject wrapper = new JsonObject();
            wrapper.add("repeat", repeat);
            nested = wrapper;
        }
        JsonArray huge = new JsonArray();
        huge.add(nested);
        assertFalse(QueueParser.parsePlan(huge, SCHEMAS, Set.of("go_to"), 32, 8192, 49152).ok());

        JsonArray tooLong = JsonParser.parseString("[{\"tool\":\"go_to\",\"args\":\"{}\"}]").getAsJsonArray();
        assertFalse(QueueParser.parsePlan(tooLong, SCHEMAS, Set.of("go_to"), 32, 8192, 10).ok());
    }

    @Test
    void rejectsAmbiguousShapesUnexpectedFieldsAndEmptyRequiredArrays() {
        for (String json : new String[] {
                "[{\"tool\":\"go_to\",\"repeat\":{}}]",
                "[{\"when\":{\"type\":\"fluid_ahead\"},\"then\":[]}]",
                "[{\"tool\":\"go_to\",\"extra\":true}]",
                "[{\"when\":{\"type\":\"fluid_ahead\"},\"then\":[],\"else\":[],\"priority\":1}]",
                "[{\"repeat\":{\"steps\":[],\"until\":{\"type\":\"fluid_ahead\"},\"max_iterations\":1}}]",
                "[{\"when\":{\"type\":\"all_of\",\"conditions\":[]},\"then\":[]}]",
                "[{\"when\":{\"type\":\"fluid_ahead\",\"unexpected\":1},\"then\":[]}]"
        }) {
            assertFalse(QueueParser.parsePlan(JsonParser.parseString(json).getAsJsonArray(), SCHEMAS,
                    Set.of("go_to"), 32, 8192, 49152).ok(), json);
        }
    }

    @Test
    void worstCaseUsesLargestBranchAndMultipliesRepeatBody() {
        JsonArray input = JsonParser.parseString("""
                [
                  {"when":{"type":"fluid_ahead"},"then":[{"tool":"go_to"}],
                   "else":[{"tool":"go_to"},{"tool":"go_to"},{"tool":"go_to"}]},
                  {"repeat":{"steps":[{"tool":"go_to"},{"tool":"go_to"}],
                   "until":{"type":"fluid_ahead"},"max_iterations":4}}
                ]
                """).getAsJsonArray();
        var parsed = QueueParser.parsePlan(input, SCHEMAS, Set.of("go_to"), 11, 8192, 49152);
        assertTrue(parsed.ok(), parsed.errors().toString());
        assertEquals(11, parsed.plan().worstCaseActions());
        assertFalse(QueueParser.parsePlan(input, SCHEMAS, Set.of("go_to"), 10, 8192, 49152).ok());
    }

    @Test
    void flatParserKeepsLegacyGuardArraysAndRejectsTreeNodes() {
        JsonArray flat = JsonParser.parseString("[{\"tool\":\"go_to\",\"when\":[{\"type\":\"fluid_ahead\"}]}]").getAsJsonArray();
        var parsed = QueueParser.parse(flat, SCHEMAS, Set.of("go_to"), 32, 8192, 49152);
        assertTrue(parsed.ok(), parsed.errors().toString());
        assertEquals(1, parsed.steps().getFirst().guards().size());
        JsonArray tree = JsonParser.parseString("[{\"when\":{\"type\":\"fluid_ahead\"},\"then\":[]}]").getAsJsonArray();
        assertFalse(QueueParser.parse(tree, SCHEMAS, Set.of("go_to"), 32, 8192, 49152).ok());
    }

    @Test
    void worstCaseActionCountSaturatesInsteadOfOverflowing() {
        var child = new PlanNode.Action(new dev.friendai.queue.QueuedStep("go_to", new JsonObject(), null,
                java.util.List.of(), dev.friendai.queue.GuardAction.WAIT, null, 1, java.util.List.of()));
        PlanNode nested = child;
        for (int i = 0; i < 20; i++) {
            nested = new PlanNode.Repeat(java.util.List.of(nested), new Guard.FluidAhead(), 64);
        }
        assertEquals(Long.MAX_VALUE, new PlanDefinition(java.util.List.of(nested)).worstCaseActions());
    }
}
