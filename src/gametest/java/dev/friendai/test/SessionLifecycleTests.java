package dev.friendai.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.plan.PlanProposal;
import dev.friendai.agent.plan.Planner;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.agent.turn.Speaker;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.trading.TradeBudget;
import dev.friendai.features.trading.TradeOrder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Lifecycle and owner-isolation guarantees for per-owner companion sessions. */
public class SessionLifecycleTests {
    private static final class DeferredGateway implements LlmGateway {
        private final CompletableFuture<LlmResponse> response = new CompletableFuture<>();

        @Override
        public CompletableFuture<LlmResponse> complete(
                String system, List<dev.friendai.agent.llm.model.Message> messages, List<ToolSpec> tools) {
            return response;
        }

        void complete(String text, String toolName) {
            List<ToolCall> calls = toolName == null
                    ? List.of()
                    : List.of(new ToolCall("late-call", toolName, new JsonObject(), "{}", null));
            response.complete(new LlmResponse(
                    text, calls, calls.isEmpty() ? StopReason.END : StopReason.TOOL_USE, new Usage(0, 0)));
        }
    }

    private static final class CaptureSpeaker implements Speaker {
        private Speaker delegate;
        private final List<String> said = new CopyOnWriteArrayList<>();

        void wrap(Speaker delegate) {
            this.delegate = delegate;
        }

        @Override
        public void say(String text) {
            said.add(text);
        }

        @Override
        public void thinking(boolean on) {
            delegate.thinking(on);
        }

        @Override
        public void error(dev.friendai.foundation.text.UserMessage message) {
            delegate.error(message);
        }
    }

    private static final class DeferredPlanner implements Planner {
        private final CompletableFuture<PlanProposal> proposal = new CompletableFuture<>();
        private boolean requested;

        @Override
        public CompletableFuture<PlanProposal> propose(dev.friendai.agent.plan.PlannerInput input) {
            requested = true;
            return proposal;
        }
    }

    @GameTest(maxTicks = 120)
    public void lateModelCompletionAfterOwnerLogoutHasNoEffects(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var sessions = GameTestSupport.runtime(h.getLevel().getServer()).sessions();
        DeferredGateway gateway = new DeferredGateway();
        CaptureSpeaker speaker = new CaptureSpeaker();
        sessions.setGatewayOverride(owner.getUUID(), gateway);
        sessions.setSpeakerWrapper(owner.getUUID(), real -> {
            speaker.wrap(real);
            return speaker;
        });
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        sessions.post(owner.getUUID(), said(owner, "please stay"));

        h.runAfterDelay(2, () -> {
            h.assertTrue(sessions.find(owner.getUUID()).orElseThrow().isThinking(), "the first turn should be pending");
            sessions.onOwnerLeft(owner.getUUID());
            gateway.complete("late reply", "stay");
            h.runAfterDelay(3, () -> {
                h.assertTrue(speaker.said.isEmpty(), "late text must not be spoken: " + speaker.said);
                h.assertTrue(!hasStay(companion), "late tool call must not start stay");
                h.succeed();
            });
        });
    }

    @GameTest(maxTicks = 160)
    public void replacedEntityRejectsOldTurnAndReceivesNewTurn(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        var sessions = GameTestSupport.runtime(server).sessions();
        CompanionManager manager = CompanionManager.get(server);
        DeferredGateway oldGateway = new DeferredGateway();
        CaptureSpeaker oldSpeaker = new CaptureSpeaker();
        sessions.setGatewayOverride(owner.getUUID(), oldGateway);
        sessions.setSpeakerWrapper(owner.getUUID(), real -> {
            oldSpeaker.wrap(real);
            return oldSpeaker;
        });
        CompanionEntity oldCompanion = manager.summon(owner);
        sessions.post(owner.getUUID(), said(owner, "first turn"));

        h.runAfterDelay(2, () -> {
            manager.dismiss(owner);
            CompanionEntity replacement = manager.summon(owner);
            sessions.onEntityReplaced(owner.getUUID());
            DeferredGateway newGateway = new DeferredGateway();
            CaptureSpeaker newSpeaker = new CaptureSpeaker();
            sessions.setGatewayOverride(owner.getUUID(), newGateway);
            sessions.setSpeakerWrapper(owner.getUUID(), real -> {
                newSpeaker.wrap(real);
                return newSpeaker;
            });
            sessions.post(owner.getUUID(), said(owner, "second turn"));
            h.runAfterDelay(2, () -> {
                h.assertTrue(
                        sessions.find(owner.getUUID()).orElseThrow().isThinking(),
                        "the replacement should own the active session");
                h.assertTrue(replacement.isThinking(), "thinking must be synced to the replacement entity");
                h.assertTrue(!oldCompanion.isThinking(), "the replaced entity must stay idle");
                oldGateway.complete("late old reply", "stay");
                h.runAfterDelay(3, () -> {
                    h.assertTrue(oldSpeaker.said.isEmpty(), "the old turn must stay silent: " + oldSpeaker.said);
                    h.assertTrue(newSpeaker.said.isEmpty(), "the pending replacement turn is not answered yet");
                    h.assertTrue(!hasStay(oldCompanion), "the old turn must not start a task");
                    newGateway.complete("replacement reply", null);
                    h.runAfterDelay(3, () -> {
                        h.assertTrue(
                                newSpeaker.said.contains("replacement reply"),
                                "new session output: " + newSpeaker.said);
                        h.assertTrue(!replacement.isThinking(), "the replacement turn should finish");
                        h.succeed();
                    });
                });
            });
        });
    }

    @GameTest(maxTicks = 160)
    public void latePlannerCompletionAfterOwnerLogoutDoesNotQueueOrSpeak(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        var sessions = GameTestSupport.runtime(server).sessions();
        var goals = GameTestSupport.runtime(server).goals();
        var board = goals.board(owner.getUUID());
        var goal = board.set("build a house", List.of(), false, server.getTickCount())
                .value();
        board.linkPlan(goal.id(), 99);
        goals.save(owner.getUUID());
        DeferredPlanner planner = new DeferredPlanner();
        CaptureSpeaker speaker = new CaptureSpeaker();
        sessions.setPlannerOverride(owner.getUUID(), planner);
        sessions.setSpeakerWrapper(owner.getUUID(), real -> {
            speaker.wrap(real);
            return speaker;
        });
        CompanionEntity companion = CompanionManager.get(server).summon(owner);
        sessions.post(owner.getUUID(), new BrainEvent.PlanBlocked(99, goal.text(), 1, "jump", "failed", 1));

        awaitPlanner(h, planner, 20, () -> {
            sessions.onOwnerLeft(owner.getUUID());
            planner.proposal.complete(jumpProposal());
            h.runAfterDelay(3, () -> {
                h.assertTrue(speaker.said.isEmpty(), "late planner turn must not speak: " + speaker.said);
                h.assertTrue(
                        companion.executionCoordinator().queue().jobs().stream()
                                .noneMatch(job -> job.tool().equals("jump")),
                        "late planner proposal must not queue work");
                h.succeed();
            });
        });
    }

    @GameTest(maxTicks = 160)
    public void ownersKeepIndependentGatewayOverridesAndTradeAuthorities(GameTestHelper h) {
        ServerPlayer first = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer second = GameTestSupport.mockPlayerAt(h, 5, 2, 2);
        var server = h.getLevel().getServer();
        var sessions = GameTestSupport.runtime(server).sessions();
        CaptureSpeaker firstSpeaker = new CaptureSpeaker();
        CaptureSpeaker secondSpeaker = new CaptureSpeaker();
        sessions.setGatewayOverride(first.getUUID(), textGateway("first owner reply"));
        sessions.setGatewayOverride(second.getUUID(), textGateway("second owner reply"));
        sessions.setSpeakerWrapper(first.getUUID(), real -> {
            firstSpeaker.wrap(real);
            return firstSpeaker;
        });
        sessions.setSpeakerWrapper(second.getUUID(), real -> {
            secondSpeaker.wrap(real);
            return secondSpeaker;
        });
        CompanionManager manager = CompanionManager.get(server);
        manager.summon(first);
        manager.summon(second);
        sessions.post(first.getUUID(), said(first, "hello"));
        sessions.post(second.getUUID(), said(second, "hello"));

        h.runAfterDelay(5, () -> {
            h.assertTrue(firstSpeaker.said.contains("first owner reply"), "first output: " + firstSpeaker.said);
            h.assertTrue(secondSpeaker.said.contains("second owner reply"), "second output: " + secondSpeaker.said);
            var firstAuthority = sessions.find(first.getUUID()).orElseThrow().tradingAuthority();
            var secondAuthority = sessions.find(second.getUUID()).orElseThrow().tradingAuthority();
            Caller firstCaller = firstAuthority.begin(new Caller(first.getUUID(), "first", AccessLevel.OWNER), true);
            Caller secondCaller =
                    secondAuthority.begin(new Caller(second.getUUID(), "second", AccessLevel.OWNER), true);
            h.assertTrue(!firstCaller.tradingScope().equals(secondCaller.tradingScope()), "scopes must be owner-local");
            TradeOrder order = order(first.getUUID(), firstCaller.tradingScope());
            boolean rejected = false;
            try {
                secondAuthority.authority().authorize(firstCaller, order);
            } catch (RuntimeException expected) {
                rejected = true;
            }
            h.assertTrue(rejected, "one owner's authority must reject the other owner's order");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 240)
    public void completedSneakingStepReleasesItsSneakScope(GameTestHelper h) {
        int floor = 300;
        GameTestSupport.platformAt(h, floor, 16);
        CompanionEntity c = h.spawn(dev.friendai.entity.FriendaiEntities.COMPANION, 2, floor + 1, 2);
        var target = c.position().add(5.0, 0.0, 0.0);
        var plan = new dev.friendai.task.plan.SequenceTask(
                "plan",
                List.of(
                        new dev.friendai.task.plan.PlanEntry(
                                "go_to",
                                context -> new dev.friendai.features.navigation.minecraft.task.GoToTask(target, true)),
                        new dev.friendai.task.plan.PlanEntry(
                                "wait", context -> new dev.friendai.features.interaction.minecraft.task.WaitTask(200))),
                () -> 100);
        c.executionCoordinator().start(plan, "run_plan");
        boolean[] secondStep = {false};
        h.succeedWhen(() -> {
            if (plan.describe().contains("step 2/2")) secondStep[0] = true;
            h.assertTrue(secondStep[0], "the plan should reach its second step");
            h.assertFalse(c.body().sneaking(), "a finished sneaking step must release its sneak");
        });
    }

    @GameTest(maxTicks = 120)
    public void terminalCleanupCannotStopTheReplacementItStarts(GameTestHelper h) {
        int floor = 320;
        GameTestSupport.platformAt(h, floor, 8);
        CompanionEntity c = h.spawn(dev.friendai.entity.FriendaiEntities.COMPANION, 2, floor + 1, 2);
        var replacement = new dev.friendai.features.interaction.minecraft.task.WaitTask(200);
        var task = new TerminalTestTask(context -> c.executionCoordinator().start(replacement, "wait_replacement"));
        c.executionCoordinator().start(task, "terminal_test");
        h.succeedWhen(() -> {
            h.assertFalse(task.stopped, "terminal cleanup must not stop the completed task again");
            h.assertTrue(
                    c.executionCoordinator()
                            .current()
                            .map(current -> current.type().equals("wait"))
                            .orElse(false),
                    "the replacement started by cleanup must keep running");
        });
    }

    @GameTest(maxTicks = 40)
    public void failedStartStillRunsTerminalCleanup(GameTestHelper h) {
        int floor = 340;
        GameTestSupport.platformAt(h, floor, 8);
        CompanionEntity c = h.spawn(dev.friendai.entity.FriendaiEntities.COMPANION, 2, floor + 1, 2);
        var task = new ThrowingStartTestTask();
        c.executionCoordinator().start(task, "throw_start");
        h.assertTrue(task.released, "a failed start must release the state acquired before the throw");
        h.succeed();
    }

    private static final class TerminalTestTask implements dev.friendai.task.Task {
        private final java.util.function.Consumer<dev.friendai.task.TaskContext> cleanup;
        private boolean stopped;

        private TerminalTestTask(java.util.function.Consumer<dev.friendai.task.TaskContext> cleanup) {
            this.cleanup = cleanup;
        }

        @Override
        public String type() {
            return "terminal_test";
        }

        @Override
        public void start(dev.friendai.task.TaskContext context) {}

        @Override
        public dev.friendai.task.TaskStatus tick(dev.friendai.task.TaskContext context) {
            return dev.friendai.task.TaskStatus.SUCCESS;
        }

        @Override
        public void stop(dev.friendai.task.TaskContext context) {
            stopped = true;
        }

        @Override
        public void onTerminal(dev.friendai.task.TaskContext context) {
            cleanup.accept(context);
        }

        @Override
        public String describe() {
            return "terminal test";
        }

        @Override
        public String resultSummary() {
            return "done";
        }
    }

    private static final class ThrowingStartTestTask implements dev.friendai.task.Task {
        private boolean released;

        @Override
        public String type() {
            return "throw_start";
        }

        @Override
        public void start(dev.friendai.task.TaskContext context) {
            throw new IllegalStateException("start failed");
        }

        @Override
        public dev.friendai.task.TaskStatus tick(dev.friendai.task.TaskContext context) {
            return dev.friendai.task.TaskStatus.RUNNING;
        }

        @Override
        public void stop(dev.friendai.task.TaskContext context) {}

        @Override
        public void onTerminal(dev.friendai.task.TaskContext context) {
            released = true;
        }

        @Override
        public String describe() {
            return "throwing start";
        }

        @Override
        public String resultSummary() {
            return "failed";
        }
    }

    private static void awaitPlanner(GameTestHelper h, DeferredPlanner planner, int remaining, Runnable action) {
        if (planner.requested) {
            action.run();
        } else if (remaining <= 0) {
            h.assertTrue(false, "planner request did not start");
        } else {
            h.runAfterDelay(1, () -> awaitPlanner(h, planner, remaining - 1, action));
        }
    }

    private static boolean hasStay(CompanionEntity companion) {
        return companion
                        .executionCoordinator()
                        .current()
                        .map(task -> task.type().equals("stay"))
                        .orElse(false)
                || companion.executionCoordinator().queue().jobs().stream()
                        .anyMatch(job -> job.tool().equals("stay"));
    }

    private static BrainEvent.PlayerSaid said(ServerPlayer owner, String text) {
        return new BrainEvent.PlayerSaid(owner.getUUID(), owner.getName().getString(), text, BrainEvent.Source.CHAT);
    }

    private static LlmGateway textGateway(String text) {
        return (system, messages, tools) ->
                CompletableFuture.completedFuture(new LlmResponse(text, List.of(), StopReason.END, new Usage(0, 0)));
    }

    private static PlanProposal jumpProposal() {
        JsonArray steps = new JsonArray();
        JsonObject step = new JsonObject();
        step.addProperty("tool", "jump");
        steps.add(step);
        var action = new QueuedStep("jump", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of());
        return new PlanProposal(
                new PlanDefinition(List.of(new PlanNode.Action(action))), steps, "hop first", "it hopped");
    }

    private static TradeOrder order(UUID owner, UUID scope) {
        return new TradeOrder(
                UUID.randomUUID(),
                owner,
                scope,
                UUID.randomUUID(),
                "offer",
                1,
                1,
                new TradeBudget(Map.of("minecraft:emerald", 1), Map.of(), Map.of()),
                Set.of(),
                false,
                true,
                100);
    }
}
