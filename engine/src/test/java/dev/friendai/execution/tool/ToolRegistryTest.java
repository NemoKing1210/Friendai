package dev.friendai.execution.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
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
                return SchemaBuilder.object()
                        .integer("count", "How many", 1, 10)
                        .required("count")
                        .build();
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

    private Tool<String> toggled(String name, BooleanSupplier enabled) {
        return new Tool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "Toggleable test tool " + name;
            }

            @Override
            public JsonObject schema() {
                return SchemaBuilder.object()
                        .integer("count", "How many", 1, 10)
                        .required("count")
                        .build();
            }

            @Override
            public AccessLevel minAccess() {
                return AccessLevel.GUEST;
            }

            @Override
            public boolean enabled() {
                return enabled.getAsBoolean();
            }

            @Override
            public ToolResult execute(String ctx, JsonObject args) {
                return ToolResult.ok("ran");
            }
        };
    }

    private Tool<String> callerEcho(String name) {
        return new Tool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "Echoes the caller";
            }

            @Override
            public JsonObject schema() {
                return SchemaBuilder.object()
                        .integer("count", "How many", 1, 10)
                        .required("count")
                        .build();
            }

            @Override
            public AccessLevel minAccess() {
                return AccessLevel.GUEST;
            }

            @Override
            public ToolResult execute(String ctx, JsonObject args) {
                return ToolResult.ok("unused");
            }

            @Override
            public ToolResult execute(String ctx, Caller caller, JsonObject args) {
                return ToolResult.ok(caller.uuid() + "/" + caller.name() + "/"
                        + caller.access().label());
            }
        };
    }

    private static ToolCall call(String name, String json) {
        return new ToolCall("1", name, JsonParser.parseString(json).getAsJsonObject(), json, null);
    }

    @Test
    void specsListRegisteredTools() {
        ToolRegistry<String> r =
                new ToolRegistry<String>().register(tool("a", false)).register(tool("b", false));
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
    void aQuotedCountRunsAsTheNumberItNames() {
        ToolExecutor ex = new ToolRegistry<String>().register(tool("a", false)).bind("ctx");

        ToolResult res = ex.execute(call("a", "{\"count\":\"3\"}"));

        assertFalse(res.error(), res.content());
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
        var r = new ToolRegistry<String>()
                .register(tool("chat", false, AccessLevel.GUEST))
                .register(tool("act", false));
        assertEquals(
                List.of("chat"),
                r.specs(AccessLevel.GUEST).stream().map(s -> s.name()).toList());
        assertEquals(
                List.of("chat", "act"),
                r.specs(AccessLevel.TRUSTED).stream().map(s -> s.name()).toList());
        assertEquals(
                List.of("chat", "act"), r.specs().stream().map(s -> s.name()).toList());
    }

    @Test
    void forbiddenCallIsAnErrorResultAndDoesNotRun() {
        ToolExecutor ex =
                new ToolRegistry<String>().register(tool("act", false)).bind("c");
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
        ToolExecutor ex =
                new ToolRegistry<String>().register(tool("act", false)).bind("c");
        assertFalse(ex.execute(call("act", "{\"count\":1}")).error());
    }

    @Test
    void disabledToolIsHiddenAndRefused() {
        boolean[] on = {false};
        ToolExecutor ex = new ToolRegistry<String>()
                .register(toggled("note", () -> on[0]))
                .bind("c");
        assertTrue(ex.specs(AccessLevel.OWNER).isEmpty());
        assertEquals(
                "Tool note is disabled.",
                ex.execute(call("note", "{\"count\":1}")).content());
        on[0] = true;
        assertEquals(
                List.of("note"),
                ex.specs(AccessLevel.OWNER).stream().map(s -> s.name()).toList());
    }

    @Test
    void callerReachesTheTool() {
        UUID id = UUID.randomUUID();
        ToolExecutor ex = new ToolRegistry<String>().register(callerEcho("who")).bind("c");
        assertEquals(
                id + "/Bob/guest",
                ex.execute(call("who", "{\"count\":1}"), new Caller(id, "Bob", AccessLevel.GUEST))
                        .content());
    }

    @Test
    void argumentsAreNormalizedBeforeValidationAndExecution() {
        Tool<String> aliased = new Tool<>() {
            @Override
            public String name() {
                return "aliased";
            }

            @Override
            public String description() {
                return "Accepts the documented alias";
            }

            @Override
            public JsonObject schema() {
                return SchemaBuilder.object()
                        .integer("count", "How many", 1, 10)
                        .required("count")
                        .build();
            }

            @Override
            public JsonObject normalizeArguments(JsonObject args) {
                if (args.has("count") || !args.has("n")) {
                    return args;
                }
                JsonObject normalized = new JsonObject();
                normalized.add("count", args.get("n"));
                return normalized;
            }

            @Override
            public ToolResult execute(String ctx, JsonObject args) {
                executed.add(ctx + ":" + args.get("count").getAsInt());
                return ToolResult.ok("done");
            }
        };
        ToolExecutor ex = new ToolRegistry<String>().register(aliased).bind("ctx");

        ToolResult res = ex.execute(call("aliased", "{\"n\":4}"));

        assertFalse(res.error());
        assertEquals(List.of("ctx:4"), executed);
    }

    @Test
    void aToolWithoutAnAliasReceivesTheSameArgumentInstance() {
        JsonObject[] received = new JsonObject[1];
        Tool<String> plain = new Tool<>() {
            @Override
            public String name() {
                return "plain";
            }

            @Override
            public String description() {
                return "Keeps its arguments";
            }

            @Override
            public JsonObject schema() {
                return SchemaBuilder.object()
                        .integer("count", "How many", 1, 10)
                        .required("count")
                        .build();
            }

            @Override
            public ToolResult execute(String ctx, JsonObject args) {
                received[0] = args;
                return ToolResult.ok("done");
            }
        };
        ToolExecutor ex = new ToolRegistry<String>().register(plain).bind("ctx");
        JsonObject parsed = JsonParser.parseString("{\"count\":2}").getAsJsonObject();

        ToolResult res = ex.execute(new ToolCall("1", "plain", parsed, parsed.toString(), null));

        assertFalse(res.error());
        assertSame(parsed, received[0]);
    }

    @Test
    void toolExceptionBecomesErrorResult() {
        ToolExecutor ex = new ToolRegistry<String>().register(tool("a", true)).bind("c");
        ToolResult res = ex.execute(call("a", "{\"count\":1}"));
        assertTrue(res.error());
        assertTrue(res.content().contains("boom"));
    }
}
