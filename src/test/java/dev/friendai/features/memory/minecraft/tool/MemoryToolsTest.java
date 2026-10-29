package dev.friendai.features.memory.minecraft.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.memory.MemoryActions;
import dev.friendai.features.memory.MemoryBook;
import dev.friendai.features.memory.MemoryLimits;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MemoryToolsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-0000000000f3");
    private static final UUID TRUSTED = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final Set<String> NAMES = Set.of("remember", "forget", "recall");

    @Test
    void memoryToolsAreHiddenWhenMemoryIsDisabledAndAvailableToEveryRoleWhenEnabled() {
        MemoryAccessStub access = new MemoryAccessStub();
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry registry = new FeatureToolRegistry(tools, definition -> {
            throw new AssertionError("Memory tools are not task-backed");
        });

        new MemoryTools(access).register(registry);
        assertTrue(tools.specs().isEmpty());

        access.enabled = true;
        assertEquals(NAMES, names(tools.specs(AccessLevel.GUEST)));
        assertEquals(NAMES, names(tools.specs(AccessLevel.TRUSTED)));
        assertEquals(NAMES, names(tools.specs(AccessLevel.OWNER)));
        assertEquals(
                NAMES,
                registry.catalogMetadata().stream().map(meta -> meta.name()).collect(Collectors.toSet()));
        assertEquals(
                JsonParser.parseString(
                        "{\"type\":\"object\",\"properties\":{\"subject\":{\"type\":\"string\",\"description\":\"'me' (the current speaker), 'owner', 'world', or a player name\"},\"category\":{\"type\":\"string\",\"description\":\"Fact category\",\"enum\":[\"PROFILE\",\"PREFERENCE\",\"PLACE\",\"PROMISE\",\"NOTE\"]},\"text\":{\"type\":\"string\",\"description\":\"One short sentence\"}},\"required\":[\"subject\",\"category\",\"text\"]}"),
                schema(tools, "remember"));
        assertEquals(
                JsonParser.parseString(
                        "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\",\"description\":\"Fact id\",\"minimum\":1,\"maximum\":2147483647}},\"required\":[\"id\"]}"),
                schema(tools, "forget"));
        assertEquals(
                JsonParser.parseString(
                        "{\"type\":\"object\",\"properties\":{\"player\":{\"type\":\"string\",\"description\":\"Player name, or 'me'\"}},\"required\":[\"player\"]}"),
                schema(tools, "recall"));

        ToolCatalog catalog = ToolCatalog.defaults();
        registry.catalogMetadata()
                .forEach(meta -> assertEquals(catalog.meta(meta.name()).orElseThrow(), meta));
    }

    @Test
    void memoryToolsUseMemoryActionsPolicyForTheCurrentCaller() {
        MemoryAccessStub access = new MemoryAccessStub();
        access.enabled = true;
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        new MemoryTools(access).register(new FeatureToolRegistry(tools, definition -> {
            throw new AssertionError("Memory tools are not task-backed");
        }));

        ToolResult denied = call(tools, "remember", rememberArgs("owner"), new Caller(GUEST, "Gus", AccessLevel.GUEST));
        ToolResult allowed =
                call(tools, "remember", rememberArgs("world"), new Caller(TRUSTED, "Tia", AccessLevel.TRUSTED));

        assertTrue(denied.error());
        assertTrue(denied.content().startsWith("You cannot remember that for this speaker (guest)"));
        assertFalse(allowed.error());
        assertTrue(allowed.content().startsWith("Remembered [1] about the world: NOTE:"));
    }

    private static ToolResult call(ToolRegistry<CompanionEntity> tools, String name, JsonObject args, Caller caller) {
        return tools.bind(null).execute(new ToolCall("id", name, args, args.toString(), null), caller);
    }

    private static JsonObject rememberArgs(String subject) {
        JsonObject args = new JsonObject();
        args.addProperty("subject", subject);
        args.addProperty("category", "NOTE");
        args.addProperty("text", "Likes cats");
        return args;
    }

    private static Set<String> names(List<ToolSpec> specs) {
        return specs.stream().map(ToolSpec::name).collect(Collectors.toSet());
    }

    private static JsonObject schema(ToolRegistry<CompanionEntity> tools, String name) {
        return tools.specs().stream()
                .filter(spec -> spec.name().equals(name))
                .findFirst()
                .orElseThrow()
                .parametersSchema();
    }

    private static final class MemoryAccessStub implements MemoryAccess {
        private final MemoryBook book = new MemoryBook();
        private boolean enabled;

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public MemoryActions actions(CompanionEntity companion, Caller caller) {
            return new MemoryActions(book, OWNER, caller, MemoryLimits.DEFAULTS, 0, name -> java.util.Optional.empty());
        }
    }
}
