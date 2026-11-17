package dev.friendai.test;

import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.server.agent.ToolCatalogTestAccess;
import dev.friendai.task.TaskState;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ToolCatalogTests {
    private static final Set<String> MEMORY_TOOLS = Set.of(
            "remember",
            "recall",
            "forget",
            "read_log",
            "logs_search",
            "recent_chat",
            "memory_overview",
            "compress_memory",
            "reset_manner");

    @GameTest
    public void every_registered_tool_has_a_catalog_entry(GameTestHelper h) {
        var runtime = GameTestSupport.runtime(h);
        var sessions = runtime.sessions();
        var config = runtime.config();
        boolean original = config.memory.enabled;
        try {
            config.memory.enabled = false;
            runtime.applyConfig(config);
            var disabled = sessions.toolRegistrations();
            Set<String> names = disabled.stream().map(r -> r.spec().name()).collect(Collectors.toSet());
            var catalog =
                    sessions.toolMetadata().stream().map(meta -> meta.name()).collect(Collectors.toSet());
            h.assertValueEqual(catalog, names, "catalog covers all registrations before filtering");
            h.assertTrue(names.containsAll(MEMORY_TOOLS), "conditional tools remain registered");
            h.assertTrue(
                    sessions.toolNames().stream().noneMatch(MEMORY_TOOLS::contains),
                    "memory tools stay hidden while disabled");
            Set<String> withoutMemory = new HashSet<>(sessions.toolNames());
            Set<String> expectedWithoutMemory = new HashSet<>(names);
            expectedWithoutMemory.removeAll(MEMORY_TOOLS);
            h.assertValueEqual(withoutMemory, expectedWithoutMemory, "only memory tools are disabled");

            config.memory.enabled = true;
            runtime.applyConfig(config);
            var enabled = sessions.toolRegistrations();
            h.assertValueEqual(
                    enabled.stream().map(r -> r.spec().name()).collect(Collectors.toSet()),
                    names,
                    "the registered names stay stable");
            Set<String> newlyEnabled = new HashSet<>(sessions.toolNames());
            newlyEnabled.removeAll(withoutMemory);
            h.assertValueEqual(newlyEnabled, MEMORY_TOOLS, "memory switch exposes the production tools");
            h.assertValueEqual(new HashSet<>(sessions.toolNames()), names, "memory enables the full registry");
            for (AccessLevel caller : AccessLevel.values()) {
                Set<String> expected = enabled.stream()
                        .filter(ToolRegistry.Registration::enabled)
                        .filter(r -> caller.allows(r.minAccess()))
                        .map(r -> r.spec().name())
                        .collect(Collectors.toSet());
                h.assertValueEqual(new HashSet<>(sessions.toolNames(caller)), expected, "caller visibility: " + caller);
            }
            h.assertTrue(sessions.toolNames(AccessLevel.GUEST).contains("look_around"), "guests can look around");
            h.assertTrue(!sessions.toolNames(AccessLevel.GUEST).contains("mine_blocks"), "guests cannot mine");
            h.assertTrue(sessions.toolNames(AccessLevel.TRUSTED).contains("mine_blocks"), "trusted callers can mine");
            h.assertTrue(!sessions.toolNames(AccessLevel.TRUSTED).contains("logs_search"), "logs belong to the owner");
            h.assertTrue(sessions.toolNames(AccessLevel.OWNER).contains("logs_search"), "the owner can inspect logs");

            var companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
            companion.setOwner(java.util.UUID.randomUUID());
            for (AccessLevel access : AccessLevel.values()) {
                var principal = access == AccessLevel.OWNER ? companion.ownerUuid() : java.util.UUID.randomUUID();
                Set<String> planned =
                        ToolCatalogTestAccess.plannerTools(sessions, companion, new Caller(principal, "", access));
                h.assertTrue(sessions.toolNames(access).containsAll(planned), "planner never widens direct access");
                h.assertTrue(
                        planned.contains("go_to") == access.allows(AccessLevel.TRUSTED), "planner movement rights");
                h.assertTrue(!planned.contains("show_inventory"), "opening inventory stays direct only");
            }
            h.assertTrue(
                    sessions.taskRestoreRegistry()
                            .restore(new TaskState("go_to", "1,64,2"), companion)
                            .filter(task -> task.type().equals("go_to"))
                            .isPresent(),
                    "runtime restore uses the contributed go_to checkpoint");
            h.assertTrue(
                    sessions.taskRestoreRegistry()
                            .restore(new TaskState("go_to", "invalid"), companion)
                            .isEmpty(),
                    "runtime restore rejects malformed checkpoint data");
        } finally {
            config.memory.enabled = original;
            runtime.applyConfig(config);
        }
        h.succeed();
    }
}
