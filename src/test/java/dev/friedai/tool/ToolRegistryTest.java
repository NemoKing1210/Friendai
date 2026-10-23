package dev.friedai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friedai.llm.model.ToolCall;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {
    private final List<String> executed = new ArrayList<>();

    private Tool<String> tool(String name, boolean throwing) {
        return tool(name, throwing, AccessLevel.TRUSTED);
    }

    private Tool<String> tool(String name, boolean throwing, AccessLevel minAccess) {
        return new Tool<>() {
            @Override
            public AccessLevel minAccess() {
                return minAccess;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "Test tool " + name;
            }

            @Override
            public JsonObject schema() {
                return SchemaBuilder.object().integer("count", "How many", 1, 10).required("count").build();
            }

            @Override
            public ToolResult execute(String ctx, JsonObject args) {
                if (throwing) {
                    throw new RuntimeException("boom");
                }
                executed.add(ctx + ":" + args.get("count").getAsInt());
                return ToolResult.ok("done");
            }
        };
    }

    private static ToolCall call(String name, String json) {
        return new ToolCall("1", name, JsonParser.parseString(json).getAsJsonObject(), json, null);
    }

    @Test
    void specsListRegisteredTools() {
        ToolRegistry<String> r = new ToolRegistry<String>().register(tool("a", false)).register(tool("b", false));
        assertEquals(List.of("a", "b"), r.specs().stream().map(s -> s.name()).toList());
        assertEquals("Test tool a", r.specs().get(0).description());
    }

    @Test
    void executesValidCall() {
        ToolExecutor ex = new ToolRegistry<String>().register(tool("a", false)).bind("ctx");
        ToolResult res = ex.execute(call("a", "{\"count\":3}"));
        assertFalse(res.error());
        assertEquals(List.of("ctx:3"), executed);
    }

    @Test
    void unknownToolIsErrorResult() {
        ToolResult res = new ToolRegistry<String>().bind("c").execute(call("fly", "{}"));
        assertEquals("Unknown tool: fly", res.content());
        assertTrue(res.error());
    }

    @Test
    void nullArgumentsBecomeErrorResult() {
        ToolExecutor ex = new ToolRegistry<String>().register(tool("a", false)).bind("c");
        ToolResult res = ex.execute(new ToolCall("1", "a", null, "{bad", null));
        assertTrue(res.error());
        assertTrue(res.content().contains("invalid JSON arguments"));
        assertTrue(executed.isEmpty());
    }

    @Test
    void invalidArgsReportedNotExecuted() {
        ToolExecutor ex = new ToolRegistry<String>().register(tool("a", false)).bind("c");
        ToolResult res = ex.execute(call("a", "{\"count\":0}"));
        assertTrue(res.error());
        assertTrue(executed.isEmpty());
    }

    @Test
    void guestSeesOnlyGuestTools() {
        var r = new ToolRegistry<String>().register(tool("chat", false, AccessLevel.GUEST)).register(tool("act", false));
        assertEquals(List.of("chat"), r.specs(AccessLevel.GUEST).stream().map(s -> s.name()).toList());
        assertEquals(List.of("chat", "act"), r.specs(AccessLevel.TRUSTED).stream().map(s -> s.name()).toList());
        assertEquals(List.of("chat", "act"), r.specs().stream().map(s -> s.name()).toList());
    }

    @Test
    void forbiddenCallIsAnErrorResultAndDoesNotRun() {
        ToolExecutor ex = new ToolRegistry<String>().register(tool("act", false)).bind("c");
        ToolResult res = ex.execute(call("act", "{\"count\":1}"), AccessLevel.GUEST);
        assertTrue(res.error());
        assertEquals("Tool act is not available to this speaker (guest).", res.content());
        assertTrue(executed.isEmpty());
    }

    @Test
    void unknownToolIsReportedBeforeAccess() {
        ToolResult res = new ToolRegistry<String>().bind("c").execute(call("fly", "{}"), AccessLevel.GUEST);
        assertEquals("Unknown tool: fly", res.content());
    }

    @Test
    void defaultExecuteActsAsOwner() {
        ToolExecutor ex = new ToolRegistry<String>().register(tool("act", false)).bind("c");
        assertFalse(ex.execute(call("act", "{\"count\":1}")).error());
    }

    @Test
    void toolExceptionBecomesErrorResult() {
        ToolExecutor ex = new ToolRegistry<String>().register(tool("a", true)).bind("c");
        ToolResult res = ex.execute(call("a", "{\"count\":1}"));
        assertTrue(res.error());
        assertTrue(res.content().contains("boom"));
    }
}
