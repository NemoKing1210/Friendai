package dev.friendai.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolContributionTest {
    private static final class Probe {
        private int executions;
    }

    private static Tool<Probe> tool(String name, AccessLevel minAccess, Probe probe) {
        return new Tool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test contribution";
            }

            @Override
            public JsonObject schema() {
                return SchemaBuilder.object()
                        .integer("count", "How many", 1, 4)
                        .required("count")
                        .build();
            }

            @Override
            public AccessLevel minAccess() {
                return minAccess;
            }

            @Override
            public ToolResult execute(Probe context, JsonObject args) {
                probe.executions++;
                return ToolResult.ok("count=" + args.get("count").getAsInt());
            }
        };
    }

    private static ToolCall call(String rawArguments, JsonObject parsedArguments) {
        return new ToolCall("1", "move", parsedArguments, rawArguments, null);
    }

    @Test
    void duplicateContributionIsRejectedWithoutReplacingTheFirstTool() {
        Probe probe = new Probe();
        ToolRegistry<Probe> registry = new ToolRegistry<Probe>().register(tool("move", AccessLevel.TRUSTED, probe));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> registry.register(tool("move", AccessLevel.GUEST, probe)));

        assertEquals("Tool already registered: move", failure.getMessage());
        var result = registry.bind(probe)
                .execute(
                        call(
                                "{\"count\":2}",
                                JsonParser.parseString("{\"count\":2}").getAsJsonObject()),
                        new Caller(null, "guest", AccessLevel.GUEST));
        assertTrue(result.error());
        assertEquals("Tool move is not available to this speaker (guest).", result.content());
        assertEquals(0, probe.executions);
    }

    @Test
    void accessAndMalformedArgumentsRemainErrorsAndCatalogMetadataKeepsItsGroup() {
        Probe probe = new Probe();
        ToolRegistry<Probe> registry = new ToolRegistry<Probe>().register(tool("move", AccessLevel.TRUSTED, probe));
        var executor = registry.bind(probe);

        var denied = executor.execute(
                call("{\"count\":2}", JsonParser.parseString("{\"count\":2}").getAsJsonObject()),
                new Caller(null, "guest", AccessLevel.GUEST));
        var malformed = executor.execute(call("{broken", null), new Caller(null, "owner", AccessLevel.OWNER));

        assertTrue(denied.error());
        assertTrue(malformed.error());
        assertEquals(0, probe.executions);
        ToolCatalog catalog = ToolCatalog.defaults();
        ToolMeta move = catalog.meta("go_to").orElseThrow();
        assertEquals(ToolGroup.MOVEMENT, move.group());
        assertEquals(Cost.MEDIUM, move.cost());
        ToolSpec movement = new ToolSpec("go_to", "", new JsonObject());
        ToolSpec unknown = new ToolSpec("uncatalogued", "", new JsonObject());
        assertTrue(catalog.namesFor(Set.of()).contains("stop"));
        assertFalse(catalog.namesFor(Set.of()).contains("go_to"));
        assertTrue(catalog.namesFor(Set.of(ToolGroup.MOVEMENT)).contains("go_to"));
        assertEquals(
                Set.of("go_to", "uncatalogued"),
                catalog.filter(List.of(movement, unknown), Set.of(ToolGroup.MOVEMENT)).stream()
                        .map(ToolSpec::name)
                        .collect(java.util.stream.Collectors.toSet()));
    }
}
