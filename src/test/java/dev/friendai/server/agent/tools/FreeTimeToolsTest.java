package dev.friendai.server.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.server.agent.FreeTimeDriver;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FreeTimeToolsTest {
    private static final Set<String> TOOL_NAMES = Set.of("start_free_time", "end_free_time", "choose_activity");

    @Test
    void contributesOwnerOnlyToolsWithTheirCatalogMetadata() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry contributions = contributions(tools);

        new FreeTimeTools(() -> {
                    throw new AssertionError("registering free-time tools must not resolve the driver");
                })
                .register(contributions);

        assertEquals(Set.of(), names(tools.specs(AccessLevel.GUEST)));
        assertEquals(Set.of(), names(tools.specs(AccessLevel.TRUSTED)));
        assertEquals(TOOL_NAMES, names(tools.specs(AccessLevel.OWNER)));
        assertEquals(
                List.of("start_free_time", "end_free_time", "choose_activity"),
                contributions.catalogMetadata().stream()
                        .map(metadata -> metadata.name())
                        .toList());
        contributions
                .catalogMetadata()
                .forEach(metadata -> assertEquals(
                        ToolCatalog.defaults().meta(metadata.name()).orElseThrow(), metadata));
        assertTrue(contributions.taskDefinitions().isEmpty());
        assertTrue(contributions.restorableTaskNames().isEmpty());
    }

    @Test
    void preservesFreeTimeDescriptionsAndSchemas() {
        ToolRegistry<CompanionEntity> tools = registry(() -> {
            throw new AssertionError("schema inspection must not resolve the driver");
        });

        assertEquals(
                "Start free time: you work on your own, like a survival player, until the owner calls you back. "
                        + "Use it when the owner says to do your own thing or whatever you want. Pass their hint as wish.",
                spec(tools, "start_free_time").description());
        assertEquals(
                "End free time and come back to the owner. Use it when the owner calls you back or tells you to stop "
                        + "working; it is not the answer to 'don't follow me', which only switches following off.",
                spec(tools, "end_free_time").description());
        assertEquals(
                "Free time only: choose the one activity you do next and the agenda need it serves. A planner turns "
                        + "it into steps.",
                spec(tools, "choose_activity").description());
        assertEquals(
                JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"wish\":{\"type\":\"string\","
                        + "\"description\":\"The owner's hint, like 'focus on food'; optional\"}}}"),
                spec(tools, "start_free_time").parametersSchema());
        assertEquals(
                JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}"),
                spec(tools, "end_free_time").parametersSchema());
        assertEquals(
                JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"goal\":{\"type\":\"string\","
                        + "\"description\":\"The activity in one short sentence, like 'mine iron for a pickaxe'\"},"
                        + "\"criteria\":{\"type\":\"array\",\"description\":\"How you will know it is done\","
                        + "\"items\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\","
                        + "\"description\":\"One check\"}},\"required\":[\"text\"]},\"minItems\":0,\"maxItems\":6},"
                        + "\"why\":{\"type\":\"string\",\"description\":\"One short sentence to tell the owner why\"},"
                        + "\"need\":{\"type\":\"string\",\"description\":\"The agenda need this activity serves\","
                        + "\"enum\":[\"SURVIVE\",\"HUNGER\",\"SHELTER\",\"SLEEP\",\"UNLOAD\",\"COLLECT\","
                        + "\"FOOD\",\"PROCESS\",\"GEAR\",\"REPAIR\",\"FARM\",\"FARM_CREATE\",\"ANIMALS\","
                        + "\"LIGHT\",\"BUILD\",\"STORAGE\",\"TRADE\",\"ENCHANT\",\"NETHER\",\"STOCK\",\"EXPLORE\"]},"
                        + "\"minutes\":{\"type\":\"integer\",\"description\":\"How long it may take\","
                        + "\"minimum\":3,\"maximum\":120}},\"required\":[\"goal\",\"why\"]}"),
                spec(tools, "choose_activity").parametersSchema());
    }

    @Test
    void rejectsInvalidActivityBeforeResolvingTheDriver() {
        AtomicInteger driverLookups = new AtomicInteger();
        ToolRegistry<CompanionEntity> tools = registry(() -> {
            driverLookups.incrementAndGet();
            throw new AssertionError("invalid activity must be rejected before resolving the driver");
        });
        JsonObject args = new JsonObject();
        args.addProperty("goal", "   ");
        args.addProperty("why", "because");

        var result = tools.bind(null)
                .execute(
                        new ToolCall("call-1", "choose_activity", args, args.toString(), null),
                        new Caller(UUID.randomUUID(), "Owner", AccessLevel.OWNER));

        assertTrue(result.error());
        assertEquals("goal must be 1 to 200 characters", result.content());
        assertEquals(0, driverLookups.get());
    }

    private static ToolRegistry<CompanionEntity> registry(java.util.function.Supplier<FreeTimeDriver> driver) {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        new FreeTimeTools(driver).register(contributions(tools));
        return tools;
    }

    private static FeatureToolRegistry contributions(ToolRegistry<CompanionEntity> tools) {
        return new FeatureToolRegistry(tools, definition -> {
            throw new AssertionError("free-time tools are not task-backed");
        });
    }

    private static Set<String> names(List<dev.friendai.agent.llm.model.ToolSpec> specs) {
        return specs.stream().map(spec -> spec.name()).collect(Collectors.toSet());
    }

    private static dev.friendai.agent.llm.model.ToolSpec spec(ToolRegistry<CompanionEntity> tools, String name) {
        return tools.specs().stream()
                .filter(spec -> spec.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
