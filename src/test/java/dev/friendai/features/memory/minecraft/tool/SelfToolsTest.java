package dev.friendai.features.memory.minecraft.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.memory.MemoryBook;
import dev.friendai.features.memory.MemoryLimits;
import dev.friendai.features.memory.transcript.Speaker;
import dev.friendai.features.memory.transcript.TranscriptBook;
import dev.friendai.features.memory.transcript.TranscriptLimits;
import dev.friendai.foundation.diagnostics.DebugLog;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelfToolsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TRUSTED = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final Caller OWNER_CALLER = new Caller(OWNER, "Alex", AccessLevel.OWNER);
    private static final Caller TRUSTED_CALLER = new Caller(TRUSTED, "Tia", AccessLevel.TRUSTED);
    private static final Caller GUEST_CALLER = new Caller(GUEST, "Gus", AccessLevel.GUEST);

    private final Stub access = new Stub();
    private ToolRegistry<CompanionEntity> tools;

    @BeforeEach
    void setUp() {
        tools = new ToolRegistry<>();
        new SelfTools(access).register(new FeatureToolRegistry(tools, definition -> {
            throw new AssertionError("Self tools are not task-backed");
        }));
        access.enabled = true;
    }

    @Test
    void theToolsAreOfferedByRoleAndHiddenWhenMemoryIsDisabled() {
        assertEquals(Set.of("read_log", "recent_chat"), names(tools.specs(AccessLevel.OWNER)));
        assertEquals(Set.of("recent_chat"), names(tools.specs(AccessLevel.TRUSTED)));
        assertEquals(Set.of(), names(tools.specs(AccessLevel.GUEST)));
        access.enabled = false;
        assertEquals(Set.of(), names(tools.specs(AccessLevel.OWNER)));
    }

    @Test
    void theContributedMetadataMatchesTheCatalogDefaults() {
        ToolRegistry<CompanionEntity> registry = new ToolRegistry<>();
        FeatureToolRegistry contributions = new FeatureToolRegistry(registry, definition -> {
            throw new AssertionError("Self tools are not task-backed");
        });
        new SelfTools(access).register(contributions);
        dev.friendai.agent.tools.ToolCatalog catalog = dev.friendai.agent.tools.ToolCatalog.defaults();
        assertEquals(2, contributions.catalogMetadata().size());
        contributions
                .catalogMetadata()
                .forEach(meta -> assertEquals(catalog.meta(meta.name()).orElseThrow(), meta));
    }

    @Test
    void theOwnerReadsTheLogWithIdsAndRedaction() {
        access.log.add(LogKind.USER, "[Alex]: hello");
        access.log.add(LogKind.TOOL_RESULT, "stay: key sk-abc");
        ToolResult result = call("read_log", args("order", "oldest"), OWNER_CALLER);
        assertFalse(result.error());
        assertTrue(result.content().contains("[1] USER: [Alex]: hello"));
        assertTrue(result.content().contains("[2] TOOL_RESULT: stay: key [redacted]"));
        assertTrue(result.content().contains("truncated: false"));
    }

    @Test
    void aTrustedPlayerNeverReadsTheBrainLog() {
        access.log.add(LogKind.USER, "private");
        ToolResult result = call("read_log", new JsonObject(), TRUSTED_CALLER);
        assertTrue(result.error());
        assertFalse(result.content().contains("private"));
    }

    @Test
    void recentChatReturnsBothSidesAndScopesByRole() {
        access.transcript.add(OWNER, "Alex", Speaker.PLAYER, "build a hut", 1_000);
        access.transcript.add(OWNER, "Alex", Speaker.COMPANION, "on it", 2_000);
        access.transcript.add(TRUSTED, "Tia", Speaker.PLAYER, "hello my secret", 3_000);

        ToolResult own = call("recent_chat", new JsonObject(), OWNER_CALLER);
        assertTrue(own.content().contains("Alex: build a hut"));
        assertTrue(own.content().contains("You: on it"));
        assertFalse(own.content().contains("Tia"));

        ToolResult other = call("recent_chat", args("player", "Tia"), OWNER_CALLER);
        assertTrue(other.content().contains("hello my [redacted]"));

        ToolResult denied = call("recent_chat", args("player", "Alex"), TRUSTED_CALLER);
        assertTrue(denied.error());
        assertFalse(denied.content().contains("build a hut"));

        assertTrue(call("recent_chat", new JsonObject(), GUEST_CALLER).error());
    }

    @Test
    void aResultNeverExceedsTheTurnBudget() {
        access.budget = 4_000;
        for (int i = 0; i < 200; i++) access.log.add(LogKind.TOOL_RESULT, "x".repeat(150) + i);
        ToolResult result = call("read_log", args("limit", 200), OWNER_CALLER);
        assertTrue(
                result.content().length() <= access.resultBudget() + 400,
                "length " + result.content().length());
        assertTrue(result.content().contains("truncated: true"));
        assertTrue(result.content().contains("continuation: read_log before="));
    }

    private ToolResult call(String name, JsonObject arguments, Caller caller) {
        return tools.bind(null).execute(new ToolCall("id", name, arguments, arguments.toString(), null), caller);
    }

    private static JsonObject args(String key, String value) {
        JsonObject args = new JsonObject();
        args.addProperty(key, value);
        return args;
    }

    private static JsonObject args(String key, int value) {
        JsonObject args = new JsonObject();
        args.addProperty(key, value);
        return args;
    }

    private static Set<String> names(List<ToolSpec> specs) {
        return specs.stream().map(ToolSpec::name).collect(Collectors.toSet());
    }

    private static final class Stub implements SelfAccess {
        final DebugLog log = new DebugLog(500, 2_000, text -> text, () -> 0L);
        final TranscriptBook transcript = new TranscriptBook(TranscriptLimits.DEFAULTS);
        final MemoryBook book = new MemoryBook();
        boolean enabled;
        int budget;

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public UUID owner(CompanionEntity companion) {
            return OWNER;
        }

        @Override
        public DebugLog log(UUID owner) {
            return log;
        }

        @Override
        public TranscriptBook transcript(UUID owner) {
            return transcript;
        }

        @Override
        public TranscriptLimits transcriptLimits() {
            return new TranscriptLimits(200, 400, 0, 2L * 1024 * 1024);
        }

        @Override
        public MemoryBook book(UUID owner) {
            return book;
        }

        @Override
        public MemoryLimits memoryLimits() {
            return MemoryLimits.DEFAULTS;
        }

        @Override
        public int promptBudget() {
            return budget;
        }

        @Override
        public int compactAtPercent() {
            return 90;
        }

        @Override
        public boolean compactionEnabled() {
            return true;
        }

        @Override
        public UnaryOperator<String> redactor() {
            return text -> text.replace("sk-abc", "[redacted]").replace("secret", "[redacted]");
        }

        @Override
        public long now() {
            return 10_000;
        }
    }
}
