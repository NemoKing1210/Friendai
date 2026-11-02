package dev.friendai.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.autonomy.ReflexStep;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.queue.PlanRecord;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.agent.FreeTimeDriver;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * The operator's "before and after" comparison, runnable: one scripted session drives the same few events the
 * twenty-minute debug window counts, then asserts and prints both counter lines.
 *
 * <p>The scenario is deliberately made of seams the product already owns, so no counter is written by the test
 * itself: a goal completion the world refuses goes through {@code finish_goal} and {@code LoopMetrics}, a repeated
 * stay goes through the free-time duplicate guard in {@link FreeTimeDriver}, an owner's stop goes through
 * {@link ExecutionCoordinator#stop(String)}, an owner's line goes through the social director's {@code ownerSpoke},
 * and a happening the server really saw (an announced advancement) reaches the same activity window through
 * {@code HappeningFeed}.
 *
 * <p>What it cannot drive deterministically, and therefore does not assert: the director's spoken-line counters
 * ({@code ordinary}/{@code urgent}/{@code held}/{@code dropped}). A line only exists once the director's cadence,
 * distance and hearing rules all agree on one tick, so the test asserts the two counters the scenario can pin down and
 * says so here instead of pretending.
 */
public class MetricsSoakTests {
    /** Answers the posted social opening locally: the test must never reach a real model. */
    private static final class Script implements LlmGateway {
        @Override
        public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
            return CompletableFuture.completedFuture(
                    new LlmResponse("I am back.", List.of(), StopReason.END, new Usage(0, 0)));
        }
    }

    private static ToolResult call(CompanionEntity c, Caller caller, String name, String json) {
        ToolCall call = new ToolCall("1", name, JsonParser.parseString(json).getAsJsonObject(), json, null);
        return GameTestToolRegistry.create(c, () -> 32).bind(c).execute(call, caller);
    }

    private static Caller owner(ServerPlayer player) {
        return new Caller(player.getUUID(), "Steve", AccessLevel.OWNER);
    }

    /** The number in front of {@code words} in a counter line, or -1 when the line does not carry it. */
    private static int counter(String summary, String words) {
        Matcher matcher = Pattern.compile("(\\d+) " + Pattern.quote(words)).matcher(summary);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /** The tick figure of the interrupt latency: {@code which} is "max" or "avg". */
    private static int latency(String summary, String which) {
        Matcher matcher = Pattern.compile("\\(max (\\d+) ticks, avg (\\d+)\\)").matcher(summary);
        if (!matcher.find()) {
            return -1;
        }
        return Integer.parseInt("max".equals(which) ? matcher.group(1) : matcher.group(2));
    }

    @GameTest(maxTicks = 1200)
    public void aScriptedSessionReportsTheLoopCounters(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        UUID ownerId = player.getUUID();
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        runtime.sessions().setGatewayOverride(ownerId, new Script());
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        ExecutionCoordinator runner = c.executionCoordinator();
        Caller owner = owner(player);

        // 1. A goal whose checkable criterion the world does not meet, then one done:true claim: the loop refuses it
        // and counts the refusal. A fresh companion holds no diamond, so the criterion cannot hold.
        h.assertFalse(
                call(
                                c,
                                owner,
                                "set_goal",
                                "{\"text\":\"get a diamond\",\"criteria\":[{\"text\":\"has a diamond\",\"type\":\"has_item\",\"item\":\"minecraft:diamond\",\"count\":1}]}")
                        .error(),
                "the goal with one checkable criterion is accepted");
        long goalId = runtime.goals().board(ownerId).primary().orElseThrow().id();
        ToolResult refused = call(c, owner, "finish_goal", "{\"id\":" + goalId + ",\"done\":true,\"reason\":\"done\"}");
        h.assertTrue(refused.error(), "an unmet criterion must refuse the completion: " + refused.content());
        h.assertTrue(
                refused.content().contains("has a diamond"),
                "the refusal names the unmet criterion: " + refused.content());

        // 2. One stay job in the queue, then the same stay offered again: the free-time duplicate guard refuses it
        // (FreeTimeDriver.refusalToStart -> WorkConflict.duplicate) and counts the refusal. The plan is marked
        // autonomous so the guard reaches its duplicate branch instead of holding for "the owner's work".
        long plan = stayPlan(h, c, ownerId);
        FreeTimeDriver freeTime = runtime.sessions().freeTime();
        var held = freeTime.refusalToStart(c, false, List.of(new ReflexStep("stay", new JsonObject())));
        h.assertTrue(held.isPresent(), "a stay the queue already holds must be refused");
        h.assertTrue(held.orElse("").contains("stay"), "the refusal names the stay: " + held.orElse(""));
        h.assertValueEqual(runner.queue().planCount(), 1, "the refused stay was not queued twice");
        h.assertTrue(runner.queue().plan(plan).isPresent(), "the first stay plan is still the one in the queue");

        // 3. One social opening, raised through the seam the director posts on, plus the two counters the director
        // can pin down: the owner speaking, and a happening the server really saw (an announced advancement lands in
        // the journal through HappeningFeed, which counts it in the same activity window).
        runtime.sessions()
                .post(
                        ownerId,
                        new BrainEvent.SocialOpening(
                                BrainEvent.SocialOpening.Urgency.ORDINARY, List.of(), "the weather", false));
        runtime.socialDirector().ownerSpoke(ownerId, h.getLevel().getGameTime());
        var advancements = runtime.server().getAdvancements();
        var advancement = advancements.get(Identifier.withDefaultNamespace("story/mine_stone"));
        h.assertTrue(advancement != null, "the root advancement must exist");
        String criterion = advancement.value().criteria().keySet().iterator().next();
        h.assertTrue(player.getAdvancements().award(advancement, criterion), "the advancement is awarded");

        // 4. The owner's stop with the stay running is one interrupt; once the queue is idle the wait is measured.
        boolean[] stopped = {false};
        h.succeedWhen(() -> {
            ExecutionCoordinator live = c.executionCoordinator();
            if (!stopped[0]) {
                h.assertTrue(
                        "stay".equals(live.currentToolName()),
                        "waiting for the queued stay to run: " + live.currentToolName());
                live.stop("test");
                stopped[0] = true;
            }
            h.assertTrue(
                    !live.hasWork(),
                    "the stop leaves nothing running or queued: " + live.queue().jobCount());
            // The queue is idle, so this tick is the one that records how long the interrupt took to clear.
            live.tick();

            // 5. Read the counters back through the public getters exactly as the debug log does.
            String loops = live.loopMetrics().summary();
            String social = runtime.socialDirector().activityOf(ownerId).summary();
            System.out.println("[friendai metrics] " + loops);
            System.out.println("[friendai metrics] " + social);

            int completionsRefused = counter(loops, "completions refused");
            int duplicateRefusals = counter(loops, "duplicate refusals");
            int interrupts = counter(loops, "interrupts (max");
            int maxTicks = latency(loops, "max");
            int avgTicks = latency(loops, "avg");
            h.assertTrue(completionsRefused >= 1, "the refused goal is counted: " + loops);
            h.assertTrue(duplicateRefusals >= 1, "the repeated stay is counted: " + loops);
            h.assertValueEqual(interrupts, 1, "one owner stop is one interrupt: " + loops);
            h.assertTrue(maxTicks >= 0 && avgTicks >= 0, "the interrupt wait is measured: " + loops);
            h.assertTrue(avgTicks <= maxTicks, "the average wait cannot exceed the longest: " + loops);

            int ownerMessages = counter(social, "owner messages");
            int eventsSeen = counter(social, "events seen");
            h.assertTrue(ownerMessages >= 1, "the owner's line is counted: " + social);
            h.assertTrue(eventsSeen >= 1, "the happening the server saw is counted: " + social);
            h.succeed();
        });
    }

    /** Queues one stay as free time's own plan, the way a reflex would, without opening a shift. */
    private static long stayPlan(GameTestHelper h, CompanionEntity c, UUID owner) {
        var builtins = dev.friendai.server.agent.tools.FriendaiTools.build(
                () -> 16, dev.friendai.features.memory.minecraft.tool.MemoryAccess.DISABLED);
        var caller = new Caller(owner, "", AccessLevel.OWNER);
        String goal = FreeTimeDriver.PLAN_PREFIX + "metrics stay";
        String json = "{\"goal\":\"" + goal + "\",\"steps\":[{\"tool\":\"stay\"}]}";
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        ToolResult queued = GameTestToolRegistry.addPlan(c, caller, args, builtins.taskTools(), false, Set.of());
        h.assertTrue(!queued.error(), "the stay plan is queued: " + queued.content());
        long plan = c.executionCoordinator().queue().plans().stream()
                .mapToLong(PlanRecord::id)
                .max()
                .orElseThrow();
        c.executionCoordinator().markAutonomousPlan(plan);
        return plan;
    }
}
