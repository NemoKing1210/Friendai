package dev.friendai.brain;

import static dev.friendai.testutil.FakeGateway.call;
import static dev.friendai.testutil.FakeGateway.calls;
import static dev.friendai.testutil.FakeGateway.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.agent.goal.GoalView;
import dev.friendai.agent.plan.PlanProposal;
import dev.friendai.agent.plan.PlannerInput;
import dev.friendai.agent.recover.FailureHandler;
import dev.friendai.queue.GuardAction;
import dev.friendai.queue.PlanDefinition;
import dev.friendai.queue.PlanNode;
import dev.friendai.queue.QueuedStep;
import dev.friendai.testutil.FakeGateway;
import dev.friendai.testutil.FakeToolExecutor;
import dev.friendai.testutil.ManualScheduler;
import dev.friendai.testutil.RecordingSpeaker;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.ToolResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompanionBrainPlannerTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String BUILD_AND_BRING = "build a house and bring iron";

    /** Records what the brain asks of the planner and answers from a scripted future. */
    private static final class FakePort implements PlannerPort {
        final List<String> failures = new ArrayList<>();
        final List<Caller> appliedFor = new ArrayList<>();
        boolean hasGoal = true;
        boolean owned = true;

        @Override
        public boolean ownsPlan(long planId) {
            return owned;
        }

        CompletableFuture<PlanProposal> answer = CompletableFuture.completedFuture(proposal());
        ToolResult applyResult = ToolResult.ok("plan #5 added: 1 jobs");

        @Override
        public Optional<PlannerInput> input(Caller caller, String failure) {
            failures.add(failure);
            if (!hasGoal) {
                return Optional.empty();
            }
            Goal goal = new Goal(1, "house", List.of(), GoalStatus.ACTIVE, 0, 0, List.of(), 0, false);
            return Optional.of(PlannerInput.promptOnly(goal, "w", "t", "", Optional.empty(), List.of(), failure));
        }

        @Override
        public CompletableFuture<PlanProposal> propose(PlannerInput input) {
            return answer;
        }

        @Override
        public ToolResult apply(Caller caller, PlanProposal proposal) {
            appliedFor.add(caller);
            return applyResult;
        }
    }

    private static PlanProposal proposal() {
        PlanDefinition plan = new PlanDefinition(List.of(new PlanNode.Action(
                new QueuedStep("noop", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of()))));
        return new PlanProposal(plan, new JsonArray(), "gather first, then build", "a finished house");
    }

    private FakeGateway gateway;
    private FakeToolExecutor tools;
    private RecordingSpeaker speaker;
    private FakePort port;
    private CompanionBrain brain;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        tools = new FakeToolExecutor();
        speaker = new RecordingSpeaker();
        port = new FakePort();
        brain = new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(8, 30, 0, 0),
                () -> gateway,
                tools,
                () -> "state",
                speaker,
                new ManualScheduler(),
                AccessResolver.of(OWNER, new HashSet<UUID>()::contains));
        brain.setPlannerPort(port);
    }

    private static BrainEvent said(String text) {
        return new BrainEvent.PlayerSaid(OWNER, "Steve", text, BrainEvent.Source.CHAT);
    }

    private void activeGoal() {
        brain.setGoalSource(caller -> new GoalView("house", List.of(), "plan #5 active", ""));
    }

    @Test
    void goalTurnCallsPlannerAfterSetGoalAndShowsTheNoteOnce() {
        gateway.enqueue(calls("", call("g1", "set_goal")));
        gateway.enqueue(text("Planning it."));
        brain.post(said(BUILD_AND_BRING));
        assertEquals(1, port.failures.size(), "one planner consultation");
        assertEquals(OWNER, port.appliedFor.get(0).uuid());
        assertEquals(AccessLevel.OWNER, port.appliedFor.get(0).access(), "applied with the turn caller's access");
        assertEquals(2, gateway.requests.size());
        String second = gateway.requests.get(1).system();
        assertTrue(second.contains("Planner: a plan was queued"), second);
        assertTrue(second.contains("gather first, then build"), second);
        assertFalse(gateway.requests.get(0).system().contains("Planner:"));
    }

    @Test
    void plannerNoteIsShownOnlyOnce() {
        gateway.enqueue(calls("", call("g1", "set_goal")));
        gateway.enqueue(calls("", call("s1", "stay")));
        gateway.enqueue(text("Done."));
        brain.post(said(BUILD_AND_BRING));
        assertTrue(gateway.requests.get(1).system().contains("Planner:"));
        assertFalse(gateway.requests.get(2).system().contains("Planner:"), "the note is consumed by one request");
    }

    @Test
    void plannerRunsAtMostOncePerTurn() {
        gateway.enqueue(calls("", call("g1", "set_goal")));
        gateway.enqueue(calls("", call("g2", "set_goal")));
        gateway.enqueue(text("Done."));
        brain.post(said(BUILD_AND_BRING));
        assertEquals(1, port.failures.size());
    }

    @Test
    void modelThatQueuedItsOwnPlanSkipsThePlanner() {
        gateway.enqueue(calls("", call("g1", "set_goal"), call("q1", "queue_add")));
        gateway.enqueue(text("Queued."));
        brain.post(said(BUILD_AND_BRING));
        assertTrue(port.failures.isEmpty());
    }

    @Test
    void simpleRequestNeverConsultsThePlanner() {
        gateway.enqueue(calls("", call("g1", "set_goal")));
        gateway.enqueue(text("Ok."));
        brain.post(said("mine some stone"));
        assertTrue(port.failures.isEmpty());
    }

    @Test
    void stopWhilePlanningDropsTheProposalSilently() {
        port.answer = new CompletableFuture<>();
        gateway.enqueue(calls("", call("g1", "set_goal")));
        brain.post(said(BUILD_AND_BRING));
        assertTrue(brain.isThinking(), "waiting for the planner");
        brain.cancel();
        port.answer.complete(proposal());
        assertTrue(port.appliedFor.isEmpty(), "nothing is queued after a stop");
        assertEquals(1, gateway.requests.size(), "no further model request");
        assertTrue(speaker.said.isEmpty());
        assertFalse(brain.isThinking());
    }

    @Test
    void plannerFailureFallsBackToTheModel() {
        port.answer = CompletableFuture.failedFuture(new IllegalStateException("401 key sk-secret"));
        gateway.enqueue(calls("", call("g1", "set_goal")));
        gateway.enqueue(text("I will do it myself."));
        brain.post(said(BUILD_AND_BRING));
        assertEquals(2, gateway.requests.size());
        String second = gateway.requests.get(1).system();
        assertTrue(second.contains("the planner is unavailable"), second);
        assertFalse(second.contains("sk-secret"), "gateway details never reach the prompt");
        assertEquals(List.of("I will do it myself."), speaker.said);
    }

    @Test
    void rejectedPlanIsReportedToTheModel() {
        port.applyResult = ToolResult.error("Invalid plan: step 1: unknown tool 'teleport'");
        gateway.enqueue(calls("", call("g1", "set_goal")));
        gateway.enqueue(text("Hm."));
        brain.post(said(BUILD_AND_BRING));
        assertTrue(gateway.requests.get(1).system().contains("could not be queued"));
    }

    @Test
    void failedPlanEventConsultsThePlannerBeforeTheModelAnswers() {
        activeGoal();
        gateway.enqueue(text("[silent]"));
        brain.post(new BrainEvent.PlanBlocked(
                5,
                "house",
                7,
                "build_structure",
                "missing materials",
                3,
                "diagnosis: MISSING_ITEM missing 3 minecraft:iron_ingot"));
        assertEquals(1, port.failures.size());
        assertTrue(port.failures.get(0).contains("missing materials"), port.failures.get(0));
        assertEquals(1, gateway.requests.size());
        assertTrue(gateway.requests.get(0).system().contains("Planner: a plan was queued"));
    }

    @Test
    void spentBudgetSkipsThePlannerAndAsksThePlayer() {
        activeGoal();
        gateway.enqueue(text("I am stuck, can you help?"));
        brain.post(new BrainEvent.PlanBlocked(
                5, "house", 7, "build_structure", "can't reach 1 2 3", 3, FailureHandler.BUDGET_SPENT));
        assertTrue(port.failures.isEmpty(), "no replans left");
        assertEquals(List.of("I am stuck, can you help?"), speaker.said);
    }

    @Test
    void aPlayerStopIsNotAReasonToReplan() {
        activeGoal();
        gateway.enqueue(text("[silent]"));
        brain.post(new BrainEvent.PlanFinished(5, "house", false, "stopped by request"));
        assertTrue(port.failures.isEmpty(), "a stopped plan must not be re-planned");
    }

    @Test
    void aFailedPlanThatIsNotTheGoalsPlanIsLeftAlone() {
        activeGoal();
        port.owned = false;
        gateway.enqueue(text("[silent]"));
        brain.post(new BrainEvent.PlanBlocked(6, "errand", 9, "go_to", "can't reach 1 2 3", 1));
        assertTrue(port.failures.isEmpty(), "plan #6 does not belong to the goal");
    }

    @Test
    void failedPlanWithoutAGoalSkipsThePlanner() {
        gateway.enqueue(text("[silent]"));
        brain.post(new BrainEvent.PlanBlocked(5, "house", 7, "build_structure", "missing materials", 3));
        assertTrue(port.failures.isEmpty());
    }

    @Test
    void noPlannerPortMeansNoExtraStep() {
        brain.setPlannerPort(PlannerPort.NONE);
        gateway.enqueue(calls("", call("g1", "set_goal")));
        gateway.enqueue(text("Ok."));
        brain.post(said(BUILD_AND_BRING));
        assertEquals(2, gateway.requests.size());
        assertFalse(gateway.requests.get(1).system().contains("Planner:"));
    }
}
