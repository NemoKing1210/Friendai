package dev.friendai.server.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RoutingToolsTest {
    @Test
    void exposesAllNonCoreGroupsAndKeepsRoutingCoreVisible() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry contributions = new FeatureToolRegistry(tools, definition -> {
            throw new AssertionError("request_tools does not register a task");
        });
        new RoutingTools().register(contributions);

        assertEquals(
                Set.of("request_tools"),
                tools.specs().stream().map(spec -> spec.name()).collect(Collectors.toSet()));
        assertFalse(tools.specs(AccessLevel.GUEST).stream().anyMatch(spec -> spec.name().equals("request_tools")));
        assertEquals(
                Arrays.stream(ToolGroup.values())
                        .filter(group -> group != ToolGroup.CORE)
                        .map(group -> group.name().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet()),
                tools
                        .specs()
                        .getFirst()
                        .parametersSchema()
                        .getAsJsonObject("properties")
                        .getAsJsonObject("group")
                        .getAsJsonArray("enum")
                        .asList()
                        .stream()
                        .map(value -> value.getAsString())
                        .collect(Collectors.toSet()));
        assertEquals(
                ToolCatalog.defaults().meta("request_tools").orElseThrow(),
                contributions.catalogMetadata().getFirst());

        JsonObject args = new JsonObject();
        args.addProperty("group", "crafting");
        var result = tools.bind(null)
                .execute(
                        new ToolCall("call-1", "request_tools", args, args.toString(), null),
                        new Caller(UUID.randomUUID(), "Owner", AccessLevel.OWNER));
        assertEquals("tools are unlocked by the brain during a turn", result.content());
        assertFalse(result.error());
    }
}
