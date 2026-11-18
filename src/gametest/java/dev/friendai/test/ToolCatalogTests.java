package dev.friendai.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.plan.PlannerParser;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.QueueLimits;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.agent.ToolCatalogTestAccess;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

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
    public void toolArgumentContractsAgreeAcrossEntryPoints(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        h.setBlock(4, 1, 4, Blocks.NETHERRACK);
        h.setBlock(4, 2, 4, Blocks.FIRE);
        var companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(java.util.UUID.randomUUID());
        var caller = new Caller(companion.ownerUuid(), "", AccessLevel.OWNER);
        var runtime = GameTestSupport.runtime(h);
        var sessions = runtime.sessions();
        var input = ToolCatalogTestAccess.plannerInput(sessions, companion, caller);
        var runner = companion.executionCoordinator();
        runner.setPaused(true);
        var tools = GameTestToolRegistry.create(companion, () -> 8).bind(companion);

        for (String tool : List.of("extinguish_fire", "harvest_honey")) {
            JsonObject base = new JsonObject();
            if ("harvest_honey".equals(tool)) base.addProperty("using", "shears");
            for (ArgumentCase sample : List.of(
                    new ArgumentCase("{}", true),
                    new ArgumentCase("{\"x\":\"4\",\"y\":2,\"z\":4}", true),
                    new ArgumentCase("{\"x\":null,\"y\":null,\"z\":null}", true),
                    new ArgumentCase("{\"x\":4,\"z\":4}", false),
                    new ArgumentCase("{\"x\":4,\"y\":null,\"z\":4}", false),
                    new ArgumentCase("{\"x\":4294967300,\"y\":2,\"z\":4}", false))) {
                JsonObject args = base.deepCopy();
                JsonParser.parseString(sample.json())
                        .getAsJsonObject()
                        .entrySet()
                        .forEach(entry -> args.add(entry.getKey(), entry.getValue()));
                String label = tool + " " + sample.json();
                ToolResult direct = invoke(tools, caller, tool, args);
                h.assertTrue(!direct.error() == sample.accepted(), "direct: " + label + ": " + direct.content());
                if (!sample.accepted()) {
                    h.assertValueEqual(runner.queue().jobCount(), 0, "invalid direct call adds no job");
                    h.assertTrue(runner.current().isEmpty(), "invalid direct call starts no task");
                }
                runner.stopAll("reset test case");

                JsonObject step = new JsonObject();
                step.addProperty("tool", tool);
                step.addProperty("args", args.toString());
                JsonArray steps = new JsonArray();
                steps.add(step);
                JsonObject plan = new JsonObject();
                plan.add("steps", steps);
                var validate = invoke(tools, caller, "plan_validate", plan);
                h.assertTrue(
                        !validate.error() == sample.accepted(), "plan_validate: " + label + ": " + validate.content());
                plan.addProperty("goal", "Check argument contract");
                var add = invoke(tools, caller, "queue_add", plan);
                h.assertTrue(!add.error() == sample.accepted(), "queue_add: " + label + ": " + add.content());
                runner.clearQueue("reset test case");
                var parsed = PlannerParser.parse(
                        plan.toString(), input.schemas(), input.normalizers(), input.allTools(), 16, 2_000, 8_000);
                h.assertTrue(parsed.ok() == sample.accepted(), "planner: " + label + ": " + parsed.errors());

                var pending = runner.queue()
                        .addDirect(new QueuedStep(tool, base, null, List.of(), GuardAction.WAIT, null, 1, List.of()));
                JsonObject edit = new JsonObject();
                edit.addProperty("action", "update");
                edit.addProperty("job", pending.id());
                edit.addProperty("args", args.toString());
                var updated = invoke(tools, caller, "queue_edit", edit);
                h.assertTrue(!updated.error() == sample.accepted(), "queue_edit: " + label + ": " + updated.content());
                if (!sample.accepted()) {
                    h.assertValueEqual(
                            runner.queue().job(pending.id()).orElseThrow().args(),
                            base,
                            "invalid edit leaves the job intact");
                }
                runner.clearQueue("reset test case");

                JobQueue saved = new JobQueue(QueueLimits.DEFAULTS);
                var job = saved.addDirect(
                        new QueuedStep(tool, args, null, List.of(), GuardAction.WAIT, null, 1, List.of()));
                boolean restored;
                try {
                    sessions.taskRestoreRegistry()
                            .stepFactories(saved)
                            .get(job.id())
                            .create(new TaskContext(companion, h.getLevel()));
                    restored = true;
                } catch (IllegalArgumentException refused) {
                    restored = false;
                }
                h.assertTrue(restored == sample.accepted(), "restored job: " + label);
            }
        }
        h.assertTrue(
                h.getBlockState(new net.minecraft.core.BlockPos(4, 2, 4)).is(Blocks.FIRE),
                "validation left the fire untouched");
        h.succeed();
    }

    private record ArgumentCase(String json, boolean accepted) {}

    private static ToolResult invoke(ToolExecutor tools, Caller caller, String name, JsonObject args) {
        return tools.execute(new ToolCall("argument-contract", name, args, args.toString(), null), caller);
    }

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
