package dev.friendai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanParserTest {
    private static final Map<String, JsonObject> STEP_SCHEMAS = Map.of(
            "drop_items", SchemaBuilder.object()
                    .string("item", "Item id")
                    .integer("count", "How many", 1, 64)
                    .required("item", "count")
                    .build(),
            "go_to", SchemaBuilder.object()
                    .number("x", "X")
                    .number("y", "Y")
                    .number("z", "Z")
                    .build());
    private static final Set<String> ALL_TOOLS = Set.of("drop_items", "go_to", "stay", "run_plan", "get_inventory");

    private static PlanParser.Result parse(String jsonArray) {
        JsonArray steps = JsonParser.parseString(jsonArray).getAsJsonArray();
        return PlanParser.parse(steps, STEP_SCHEMAS, ALL_TOOLS);
    }

    @Test
    void validPlan() {
        var r = parse("[{\"tool\":\"drop_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:dirt\\\",\\\"count\\\":2}\"},{\"tool\":\"go_to\",\"args\":\"{\\\"x\\\":1,\\\"y\\\":2,\\\"z\\\":3}\"}]");
        assertTrue(r.ok());
        assertEquals("drop_items", r.steps().get(0).tool());
        assertEquals(2, r.steps().get(0).args().get("count").getAsInt());
        assertEquals(3, r.steps().get(1).args().get("z").getAsInt());
    }

    @Test
    void missingOrBlankArgsBecomeEmptyObject() {
        var r = parse("[{\"tool\":\"go_to\"},{\"tool\":\"go_to\",\"args\":\"  \"}]");
        assertTrue(r.ok());
        assertEquals(new JsonObject(), r.steps().get(0).args());
        assertEquals(new JsonObject(), r.steps().get(1).args());
    }

    @Test
    void invalidJsonArgs() {
        var r = parse("[{\"tool\":\"go_to\",\"args\":\"{x:\"}]");
        assertEquals(1, r.errors().size());
        assertTrue(r.errors().get(0).startsWith("step 1: args is not a JSON object"));
    }

    @Test
    void argsArrayIsRejected() {
        assertTrue(parse("[{\"tool\":\"go_to\",\"args\":\"[1,2]\"}]").errors().get(0).startsWith("step 1: args is not a JSON object"));
    }

    @Test
    void unknownTool() {
        assertEquals(List.of("step 1: unknown tool 'fly'"), parse("[{\"tool\":\"fly\"}]").errors());
    }

    @Test
    void nonPlannableTools() {
        assertEquals(List.of("step 1: 'stay' cannot be used in a plan", "step 2: 'run_plan' cannot be used in a plan"),
                parse("[{\"tool\":\"stay\"},{\"tool\":\"run_plan\"}]").errors());
    }

    @Test
    void stepArgumentErrorsCarryStepNumber() {
        assertEquals(List.of("step 2: missing required argument 'count'"),
                parse("[{\"tool\":\"go_to\"},{\"tool\":\"drop_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:dirt\\\"}\"}]").errors());
    }

    @Test
    void stepCountLimits() {
        assertEquals(List.of("a plan needs at least 1 step"), parse("[]").errors());
        String seventeen = "[" + String.join(",", Collections.nCopies(17, "{\"tool\":\"go_to\"}")) + "]";
        assertEquals(List.of("a plan can have at most 16 steps"), parse(seventeen).errors());
    }

    @Test
    void severalErrorsReportedTogether() {
        var r = parse("[{\"tool\":\"fly\"},{\"tool\":\"stay\"},{\"tool\":\"go_to\",\"args\":\"nope\"}]");
        assertEquals(3, r.errors().size());
        assertTrue(r.steps().isEmpty());
    }

    @Test
    void nonObjectStepOrMissingTool() {
        assertEquals(List.of("step 1: must be an object with a 'tool' string", "step 2: must be an object with a 'tool' string"),
                parse("[5,{\"args\":\"{}\"}]").errors());
    }
}
