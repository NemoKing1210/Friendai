package dev.friendai.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.plan.PlanProposal;
import dev.friendai.agent.recover.FailureHandler;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.foundation.diagnostics.LogEntry;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.server.agent.CompanionSessions;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

public class RecoveryTests {
    private static final String FAILING_PLAN =
            "{\"goal\":\"tools\",\"steps\":[{\"tool\":\"craft_item\",\"args\":\"{\\\"item\\\":\\\"minecraft:diamond_pickaxe\\\",\\\"count\\\":1}\"}]}";

    private static ToolResult call(CompanionEntity c, Caller caller, String name, String json) {
        ToolCall call = new ToolCall("1", name, JsonParser.parseString(json).getAsJsonObject(), json, null);
        return GameTestToolRegistry.create(c, () -> 32).bind(c).execute(call, caller);
    }

    private static LlmGateway silent() {
        return (system, messages, tools) -> CompletableFuture.completedFuture(
                new LlmResponse("[silent]", List.of(), StopReason.END, new Usage(0, 0)));
    }

    private static boolean logHas(CompanionSessions brains, ServerPlayer player, String text) {
        List<LogEntry> log = brains.debug().logOf(player.getUUID()).after(0, 200);
        return log.stream().anyMatch(e -> e.kind() == LogKind.TASK && e.text().contains(text));
    }

    @GameTest(maxTicks = 400)
    public void failed_plan_event_carries_the_diagnosis(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        CompanionSessions brains = GameTestSupport.runtime(server).sessions();
        brains.setGatewayOverride(player.getUUID(), silent());
        CompanionEntity c = CompanionManager.get(server).summon(player);
        // The brain, and with it the task listener, is created on its first event.
        brains.post(player.getUUID(), new dev.friendai.agent.turn.BrainEvent.WorldNotice("test"));
        Caller owner = new Caller(player.getUUID(), "Steve", AccessLevel.OWNER);
        h.assertFalse(call(c, owner, "set_goal", "{\"text\":\"get a pickaxe\"}").error(), "goal set");
        ToolResult r = call(c, owner, "queue_add", FAILING_PLAN);
        h.assertFalse(r.error(), "queue_add: " + r.content());
        h.succeedWhen(() -> h.assertTrue(
                logHas(brains, player, "diagnosis: MISSING_ITEM"),
                "the plan event should carry a diagnosis; log: "
                        + brains.debug().logOf(player.getUUID()).after(0, 200)));
    }

    /**
     * The event reaches the log through tick-deferred hops only: the task has to fail, the queue has to settle it and
     * the brain has to open a turn. Four hundred ticks is enough locally and was not under the shard's load.
     */
    @GameTest(maxTicks = 1200)
    public void spent_replan_budget_blocks_the_goal_and_asks_the_player(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        ServerConfig original = state.config();
        ServerConfig noReplans = ServerConfig.defaults();
        noReplans.agent.maxReplans = 0;
        state.applyConfig(noReplans);
        CompanionSessions brains = state.sessions();
        brains.setGatewayOverride(player.getUUID(), silent());
        CompanionEntity c = CompanionManager.get(server).summon(player);
        // The brain, and with it the task listener, is created on its first event.
        brains.post(player.getUUID(), new dev.friendai.agent.turn.BrainEvent.WorldNotice("test"));
        Caller owner = new Caller(player.getUUID(), "Steve", AccessLevel.OWNER);
        h.assertFalse(call(c, owner, "set_goal", "{\"text\":\"get a pickaxe\"}").error(), "goal set");
        h.assertFalse(call(c, owner, "queue_add", FAILING_PLAN).error(), "queue_add");
        h.succeedWhen(() -> {
            h.assertTrue(logHas(brains, player, "replan budget spent"), "the event should say the budget is spent");
            h.assertTrue(
                    state.goals()
                                    .board(player.getUUID())
                                    .primary()
                                    .orElseThrow()
                                    .status()
                            == GoalStatus.BLOCKED,
                    "the goal should be blocked");
            state.applyConfig(original);
        });
    }

    /**
     * The pure failure-handler cases, through the real task listener: the first unreachable route offers a remedy,
     * the replanned route that fails again spends the last replan, and that event names the blocker without repeating
     * the remedy it already tried.
     */
    @GameTest(maxTicks = 2400)
    public void a_failed_route_changes_its_remedy_then_names_the_blocker(GameTestHelper h) {
        GameTestSupport.platform(h, 14);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 12, 2, 12);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        ServerConfig original = state.config();
        ServerConfig oneReplan = ServerConfig.defaults();
        oneReplan.agent.maxReplans = 1;
        state.applyConfig(oneReplan);
        CompanionSessions brains = state.sessions();
        brains.setGatewayOverride(player.getUUID(), silent());
        AtomicInteger asked = new AtomicInteger();
        BlockPos secondSpot = h.absolutePos(new BlockPos(2, 2, 12));
        brains.setPlannerOverride(player.getUUID(), input -> {
            asked.incrementAndGet();
            return CompletableFuture.completedFuture(new PlanProposal(
                    new PlanDefinition(List.of(new PlanNode.Action(goToStep(secondSpot)))),
                    steps(goToStepJson(secondSpot)),
                    "walk a different way",
                    "arrive on the far side"));
        });
        CompanionEntity c = CompanionManager.get(server).summon(player);
        // A body that cannot leave its cell: bedrock on every side makes both routes impossible.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                h.setBlock(2 + dx, 1, 2 + dz, Blocks.BEDROCK);
                h.setBlock(2 + dx, 4, 2 + dz, Blocks.BEDROCK);
                if (dx != 0 || dz != 0) {
                    h.setBlock(2 + dx, 2, 2 + dz, Blocks.BEDROCK);
                    h.setBlock(2 + dx, 3, 2 + dz, Blocks.BEDROCK);
                }
            }
        }
        BlockPos cell = h.absolutePos(new BlockPos(2, 2, 2));
        c.snapTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, 0, 0);
        brains.post(player.getUUID(), new dev.friendai.agent.turn.BrainEvent.WorldNotice("ready"));
        Caller owner = new Caller(player.getUUID(), "Steve", AccessLevel.OWNER);
        h.assertFalse(
                call(c, owner, "set_goal", "{\"text\":\"walk to the far side\"}")
                        .error(),
                "goal set");
        JsonObject first = new JsonObject();
        first.addProperty("goal", "walk");
        first.add("steps", steps(goToStepJson(h.absolutePos(new BlockPos(10, 2, 10)))));
        h.assertFalse(call(c, owner, "queue_add", first.toString()).error(), "the first route is queued");
        h.succeedWhen(() -> {
            h.assertTrue(asked.get() == 1, "the failed route is replanned exactly once, asked=" + asked.get());
            List<String> failureLines = brains.debug().logOf(player.getUUID()).after(0, 400).stream()
                    .filter(e -> e.kind() == LogKind.TASK)
                    .map(LogEntry::text)
                    .filter(text -> text.contains("diagnosis: UNREACHABLE"))
                    .toList();
            h.assertTrue(failureLines.size() >= 2, "both route failures must reach the log: " + failureLines);
            String firstFailure = failureLines.stream()
                    .filter(text -> !text.contains(FailureHandler.BUDGET_SPENT))
                    .findFirst()
                    .orElse("");
            String blocked = failureLines.stream()
                    .filter(text -> text.contains(FailureHandler.BUDGET_SPENT))
                    .findFirst()
                    .orElse("");
            h.assertTrue(
                    firstFailure.contains("try: go_to a closer open spot first"),
                    "the first failure offers a remedy: " + firstFailure);
            h.assertTrue(
                    blocked.contains("blocker: target not reachable (can't reach "),
                    "the spent budget names the blocker: " + blocked);
            h.assertTrue(blocked.contains("try: try a different target"), "the remedy changes: " + blocked);
            h.assertFalse(
                    blocked.contains("go_to a closer open spot first"),
                    "the tried remedy is offered again: " + blocked);
            h.assertTrue(
                    state.goals()
                                    .board(player.getUUID())
                                    .primary()
                                    .orElseThrow()
                                    .status()
                            == GoalStatus.BLOCKED,
                    "the goal is blocked once the budget is spent");
            state.applyConfig(original);
        });
    }

    /** A {@code queue_add}-shaped step: its args are the JSON object string the queue parser demands. */
    private static JsonObject goToStepJson(BlockPos to) {
        JsonObject args = new JsonObject();
        args.addProperty("x", to.getX());
        args.addProperty("y", to.getY());
        args.addProperty("z", to.getZ());
        JsonObject step = new JsonObject();
        step.addProperty("tool", "go_to");
        step.addProperty("args", args.toString());
        return step;
    }

    private static QueuedStep goToStep(BlockPos to) {
        JsonObject args = new JsonObject();
        args.addProperty("x", to.getX());
        args.addProperty("y", to.getY());
        args.addProperty("z", to.getZ());
        return new QueuedStep("go_to", args, null, List.of(), GuardAction.WAIT, null, 1, List.of());
    }

    private static JsonArray steps(JsonObject... steps) {
        JsonArray array = new JsonArray();
        for (JsonObject step : steps) {
            array.add(step);
        }
        return array;
    }
}
