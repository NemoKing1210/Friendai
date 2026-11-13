package dev.friendai.agent.turn;

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
import dev.friendai.agent.goal.RiskClass;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.plan.PlanFailure;
import dev.friendai.agent.plan.PlanProposal;
import dev.friendai.agent.plan.PlanSubject;
import dev.friendai.agent.plan.PlannerException;
import dev.friendai.agent.plan.PlannerInput;
import dev.friendai.agent.recover.FailureHandler;
import dev.friendai.agent.route.PlannerMode;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.testutil.FakeGateway;
import dev.friendai.testutil.FakeToolExecutor;
import dev.friendai.testutil.ManualScheduler;
import dev.friendai.testutil.RecordingSpeaker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
        final List<PlanSubject> subjects = new ArrayList<>();
        final List<PlannerInput> proposed = new ArrayList<>();
        final List<java.util.function.BooleanSupplier> cancellation = new ArrayList<>();
        boolean hasGoal = true;
        boolean owned = true;
        Optional<PlannerPort.Refusal> refusal = Optional.empty();
        /** Plans that are still queued; empty means every plan the fake is asked about is owned. */
        final Set<Long> ownedPlans = new HashSet<>();

        @Override
        public boolean ownsPlan(long planId) {
            return owned && (ownedPlans.isEmpty() || ownedPlans.contains(planId));
        }

        CompletableFuture<PlanProposal> answer = CompletableFuture.completedFuture(proposal());
        ToolResult applyResult = ToolResult.ok("plan #5 added: 1 jobs");

        @Override
        public Optional<PlannerPort.Refusal> preflight(PlanSubject subject, Caller caller) {
            return refusal;
        }

        @Override
        public Optional<PlannerInput> inputFor(PlanSubject subject, Caller caller, String failure) {
            failures.add(failure);
            subjects.add(subject);
            if (!hasGoal && subject instanceof PlanSubject.ActiveGoal) {
                return Optional.empty();
            }
            Goal goal = new Goal(1, "house", List.of(), GoalStatus.ACTIVE, 0, 0, List.of(), 0, false);
            return Optional.of(PlannerInput.promptOnly(goal, "w", "t", "", Optional.empty(), List.of(), failure));
        }

        @Override
        public CompletableFuture<PlanProposal> propose(PlannerInput input) {
            proposed.add(input);
            return answer;
        }

        @Override
        public CompletableFuture<PlanProposal> propose(
                PlannerInput input, java.util.function.BooleanSupplier cancelled) {
            cancellation.add(cancelled);
            return propose(input);
        }

        @Override
        public ToolResult apply(Caller caller, PlanSubject subject, PlanProposal proposal) {
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

    @Test
    void anUnpatchableFrameHasAnExplicitFallbackWithoutBuildingInputOrCallingThePlanner() {
        port.refusal = Optional.of(new PlannerPort.Refusal(
                "structured-plan-not-patchable",
                "Keep plan #3; fix the cause, then use queue_edit action=retry on job #4."));
        List<PlannerAttempt> attempts = new ArrayList<>();
        brain.setObserver(new BrainObserver() {
            @Override
            public void onPlannerAttempt(PlannerAttempt attempt) {
                attempts.add(attempt);
            }
        });
        gateway.enqueue(text("The path is blocked."));
        brain.post(new BrainEvent.PlanBlocked(3, "deliver tools", 4, "give_items", "no route", 2));

        assertTrue(port.subjects.isEmpty(), "input creation must not spend the replan budget");
        assertTrue(port.proposed.isEmpty(), "an impossible patch must not cost a provider request");
        assertEquals("structured-plan-not-patchable", attempts.getFirst().failureCode());
        assertTrue(gateway.requests.getFirst().system().contains("Keep plan #3; fix the cause"));
        assertTrue(gateway.requests.getFirst().system().contains("queue_edit action=retry on job #4"));
    }

    @Test
    void plannerTurnHistoryStaysInTheOwnerConversation() {
        UUID guest = UUID.fromString("00000000-0000-0000-0000-000000000002");
        gateway.enqueue(calls("", call("g1", "set_goal")));
        gateway.enqueue(text("PLANNER_REPLY"));
        gateway.enqueue(text("guest reply"));
        brain.post(said(BUILD_AND_BRING));
        brain.post(new BrainEvent.PlayerSaid(guest, "Bob", "GUEST_LINE", BrainEvent.Source.CHAT));
        List<Message> messages = gateway.requests.get(2).messages();
        assertEquals("[Bob (guest)]: GUEST_LINE", messages.getLast().text());
        assertFalse(
                messages.stream()
                        .anyMatch(m ->
                                m.text().contains("PLANNER_REPLY") || m.text().contains(BUILD_AND_BRING)),
                "a planner turn's owner history must not reach a guest request");
    }

    @Test
    void plannerAlwaysConsultsOnASimpleActionableOrder() {
        plannerMode(PlannerMode.ALWAYS);
        gateway.enqueue(text("On it."));

        brain.post(said("mine some iron"));

        assertEquals(1, port.proposed.size(), "always plans an actionable order, however simple");
        assertTrue(port.subjects.get(0) instanceof PlanSubject.OwnerOrder, "the order is the subject");
    }

    @Test
    void plannerOffNeverConsultsEvenForAComplexOrder() {
        plannerMode(PlannerMode.OFF);
        gateway.enqueue(text("I will do it myself."));

        brain.post(said(BUILD_AND_BRING));

        assertTrue(port.proposed.isEmpty(), "off suppresses the planner the brain would otherwise ask");
        assertTrue(port.failures.isEmpty());
        assertEquals(1, gateway.requests.size(), "the model answers without a plan");
    }

    private void plannerMode(PlannerMode mode) {
        AgentSettings defaults = AgentSettings.DEFAULTS;
        brain.setAgentSettings(() -> new AgentSettings(
                defaults.toolRouting(),
                defaults.autonomy(),
                defaults.maxReplans(),
                defaults.goalTickSec(),
                defaults.promptBudget(),
                defaults.plannerTimeoutSec(),
                mode));
    }

    private static BrainEvent said(String text) {
        return new BrainEvent.PlayerSaid(OWNER, "Steve", text, BrainEvent.Source.CHAT);
    }

    private void activeGoal() {
        brain.setGoalSource(caller -> new GoalView("house", List.of(), "plan #5 active", "", RiskClass.CAREFUL));
    }

    @Test
    void plannerNoteIsShownOnlyOnce() {
        gateway.enqueue(calls("", call("s1", "stay")));
        gateway.enqueue(text("Done."));
        brain.post(said(BUILD_AND_BRING));
        assertTrue(gateway.requests.get(0).system().contains("Planner:"));
        assertFalse(gateway.requests.get(1).system().contains("Planner:"), "the note is consumed by one request");
    }

    @Test
    void aModelSetGoalAcrossStepsDoesNotConsultThePlanner() {
        gateway.enqueue(calls("", call("g1", "set_goal")));
        gateway.enqueue(calls("", call("g2", "set_goal")));
        gateway.enqueue(text("Done."));
        brain.post(said("mine some stone"));
        assertTrue(port.failures.isEmpty(), "a goal the model sets is not planned by the planner");
        assertEquals(3, gateway.requests.size());
    }

    @Test
    void modelThatQueuedItsOwnPlanSkipsThePlanner() {
        gateway.enqueue(calls("", call("g1", "set_goal"), call("q1", "queue_add")));
        gateway.enqueue(text("Queued."));
        brain.post(said("mine some stone"));
        assertTrue(port.failures.isEmpty());
    }

    @Test
    void aPlanQueuedInAnEarlierStepSuppressesThePlanner() {
        JsonObject goalArgsWithPlan7 = new JsonObject();
        goalArgsWithPlan7.addProperty("text", "mine some stone");
        goalArgsWithPlan7.addProperty("plan", 7);
        tools.results.put("queue_add", new ArrayDeque<>(List.of(ToolResult.ok("plan #7 added: 1 jobs"))));
        gateway.enqueue(calls("", call("q1", "queue_add")));
        gateway.enqueue(
                calls("", new ToolCall("g1", "set_goal", goalArgsWithPlan7, goalArgsWithPlan7.toString(), null)));
        gateway.enqueue(text("Queued."));

        brain.post(said("mine some stone"));

        assertTrue(port.failures.isEmpty(), "the turn already queued its plan");
        assertEquals(3, gateway.requests.size());
    }

    @Test
    void aPlanQueuedInTheSameResponseSuppressesThePlanner() {
        gateway.enqueue(calls("Queued the plan.", call("g1", "set_goal"), call("q1", "queue_add")));
        brain.post(said("mine some stone"));

        assertTrue(port.failures.isEmpty(), "set_goal after queue_add does not ask for a second plan");
        assertEquals(1, gateway.requests.size());
    }

    @Test
    void aPreTurnPlanIsShownOnceWithTheDoNotDuplicateRule() {
        gateway.enqueue(text("Running it."));

        brain.post(said(BUILD_AND_BRING));

        assertEquals(1, gateway.requests.size(), "the queued plan needs no extra request");
        String system = gateway.requests.get(0).system();
        assertTrue(system.contains("a plan was queued (plan #5 added: 1 jobs)"), system);
        assertTrue(system.contains("run it, do not queue another"), system);
        assertTrue(
                system.contains("If the planner already queued the work, use it and do not add a duplicate"), system);
        assertEquals(OWNER, port.appliedFor.get(0).uuid());
        assertEquals(AccessLevel.OWNER, port.appliedFor.get(0).access(), "applied with the turn caller's access");
    }

    @Test
    void goal_with_a_failed_queue_add_does_not_consult_the_planner() {
        tools.results.put("queue_add", new ArrayDeque<>(List.of(ToolResult.error("Invalid arguments for queue_add"))));
        gateway.enqueue(calls("", call("g1", "set_goal"), call("q1", "queue_add")));
        gateway.enqueue(text("I will plan it myself."));

        brain.post(said("make me and yourself stone tools"));

        assertTrue(port.failures.isEmpty(), "a failed model queue_add must not ask the planner for a plan");
        assertEquals(2, gateway.requests.size());
        assertTrue(
                gateway.requests.get(1).messages().stream()
                        .anyMatch(message -> message.text().contains("Invalid arguments for queue_add")),
                "the queue_add error reaches the model's next request");
    }

    @Test
    void simpleRequestNeverConsultsThePlanner() {
        gateway.enqueue(text("Ok."));
        brain.post(said("mine some stone"));
        assertTrue(port.failures.isEmpty());
    }

    @Test
    void stopWhilePlanningDropsTheProposalSilently() {
        port.answer = new CompletableFuture<>();
        brain.post(said(BUILD_AND_BRING));
        assertTrue(brain.isThinking(), "waiting for the planner");
        assertEquals(0, gateway.requests.size(), "the model is not asked while the planner works");
        brain.cancel();
        port.answer.complete(proposal());
        assertTrue(port.appliedFor.isEmpty(), "nothing is queued after a stop");
        assertEquals(0, gateway.requests.size(), "no model request after a stop");
        assertTrue(speaker.said.isEmpty());
        assertFalse(brain.isThinking());
    }

    @Test
    void failureWhilePlanningReachesTheModelBeforeApplyingTheProposal() {
        port.answer = new CompletableFuture<>();
        gateway.enqueue(text("The route is blocked."));
        brain.post(said(BUILD_AND_BRING));
        brain.post(new BrainEvent.TaskFinished(7, "go_to", false, "no safe route"));
        port.answer.complete(proposal());

        assertTrue(port.appliedFor.isEmpty());
        assertEquals(1, gateway.requests.size());
        assertTrue(gateway.requests.getFirst().messages().stream()
                .anyMatch(message -> message.text().contains("go_to failed: no safe route")));
        assertEquals(List.of("The route is blocked."), speaker.said);
    }

    @Test
    void aPlannedTurnRecordsItsRouteAndPlannerAnswer() {
        List<String> recorded = new ArrayList<>();
        brain.setObserver(new BrainObserver() {
            @Override
            public void onRouteDecision(dev.friendai.agent.route.RouteDecision decision) {
                recorded.add("route:" + decision.planning().need() + ":"
                        + decision.planning().subject().getClass().getSimpleName());
            }

            @Override
            public void onPlannerAttempt(PlannerAttempt attempt) {
                recorded.add("plan:" + attempt.disposition() + ":" + attempt.planId());
            }
        });
        gateway.enqueue(text("On it."));

        brain.post(said("build a house and bring iron"));

        assertEquals(
                List.of("route:REQUIRED:OwnerOrder", "plan:request:0", "plan:delivered:5"),
                recorded,
                "one routing record and the planner's start and outcome");
    }

    @Test
    void aStoppedTurnTellsThePlannerItWasCancelled() {
        port.answer = new CompletableFuture<>();
        brain.post(said(BUILD_AND_BRING));
        assertTrue(!port.cancellation.getFirst().getAsBoolean(), "the planner works while the turn is live");
        brain.cancel();
        assertTrue(port.cancellation.getFirst().getAsBoolean(), "the planner is told the turn was stopped");
    }

    @Test
    void plannerFailureFallsBackToTheModel() {
        port.answer = CompletableFuture.failedFuture(new IllegalStateException("401 key sk-secret"));
        gateway.enqueue(text("I will do it myself."));
        brain.post(said(BUILD_AND_BRING));
        assertEquals(1, gateway.requests.size());
        String system = gateway.requests.get(0).system();
        assertTrue(system.contains("no plan was drafted (REQUEST_FAILED)"), system);
        assertTrue(system.contains("plan the next step yourself"), system);
        assertFalse(system.contains("sk-secret"), "gateway details never reach the prompt");
        assertEquals(List.of("I will do it myself."), speaker.said);
    }

    @Test
    void rejectedPlanIsReportedToTheModel() {
        port.applyResult = ToolResult.error("Invalid plan: step 1: unknown tool 'teleport'");
        gateway.enqueue(text("Hm."));
        brain.post(said(BUILD_AND_BRING));
        assertTrue(gateway.requests.get(0).system().contains("could not be queued"));
        assertTrue(gateway.requests.get(0).system().contains("plan it yourself"));
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
    void aGoalTickPlansOnlyWhenTheGoalHasNoQueuedWork() {
        java.util.concurrent.atomic.AtomicReference<GoalView> view =
                new java.util.concurrent.atomic.AtomicReference<>(new GoalView(
                        "stock the chest",
                        List.of("2 logs missing"),
                        "plan #5 active",
                        "",
                        RiskClass.CAREFUL,
                        "",
                        5,
                        false));
        brain.setGoalSource(caller -> view.get());
        gateway.enqueue(text("Continuing."));
        brain.post(new BrainEvent.GoalTick("stock the chest", List.of("2 logs missing")));
        assertEquals(1, port.proposed.size(), "an unmet goal with no queued work is planned");
        assertTrue(port.subjects.get(0) instanceof PlanSubject.ActiveGoal, "the active goal is the subject");
        view.set(new GoalView(
                "stock the chest", List.of("2 logs missing"), "plan #6 running", "", RiskClass.CAREFUL, "", 5, true));
        gateway.enqueue(text("Continuing."));
        brain.post(new BrainEvent.GoalTick("stock the chest", List.of("2 logs missing")));
        assertEquals(1, port.proposed.size(), "a goal with queued work is not re-planned");
    }

    @Test
    void aGoalTickWithoutUnmetCriteriaDoesNotPlan() {
        brain.setGoalSource(caller ->
                new GoalView("stock the chest", List.of(), "plan #5 active", "", RiskClass.CAREFUL, "", 5, false));
        gateway.enqueue(text("Ok."));
        brain.post(new BrainEvent.GoalTick("stock the chest", List.of()));
        assertTrue(port.proposed.isEmpty());
    }

    @Test
    void aPlayerStopIsNotAReasonToReplan() {
        activeGoal();
        gateway.enqueue(text("[silent]"));
        brain.post(new BrainEvent.PlanFinished(5, "house", false, "stopped by request"));
        assertTrue(port.failures.isEmpty(), "a stopped plan must not be re-planned");
    }

    @Test
    void aPlanWhoseAuthorityIsGoneIsLeftAlone() {
        activeGoal();
        port.owned = false;
        gateway.enqueue(text("[silent]"));
        brain.post(new BrainEvent.PlanBlocked(6, "errand", 9, "go_to", "can't reach 1 2 3", 1));
        assertTrue(port.failures.isEmpty(), "a plan whose principal no longer holds it is not replanned");
    }

    @Test
    void aBatchNamesTheFirstTroubledPlanThatStillExists() {
        // Plan #3 was cancelled before the batch drained; #5 is still queued and repairable.
        port.ownedPlans.add(5L);
        port.answer = new CompletableFuture<>();
        gateway.enqueue(text("On it."));
        gateway.enqueue(text("[silent]"));
        // Keep the brain busy so both blockers land in one batch; distinct tools and reasons keep them audible.
        brain.post(said(BUILD_AND_BRING));
        brain.post(new BrainEvent.PlanBlocked(3, "house", 7, "go_to", "can't reach 1 2 3", 3));
        brain.post(new BrainEvent.PlanBlocked(5, "house", 7, "build_structure", "missing materials", 3));
        port.answer.complete(proposal());

        assertEquals(
                new PlanSubject.TroubledPlan(5, ""),
                port.subjects.getLast(),
                "the live plan is the subject, not the cancelled one");
        assertEquals(
                1,
                port.subjects.stream()
                        .filter(PlanSubject.TroubledPlan.class::isInstance)
                        .count(),
                "one consultation, and it is the live plan");
    }

    @Test
    void failedPlanWithoutAGoalIsStillReplanned() {
        port.hasGoal = false;
        gateway.enqueue(text("[silent]"));
        brain.post(new BrainEvent.PlanBlocked(5, "house", 7, "build_structure", "missing materials", 3));
        assertEquals(1, port.failures.size(), "a troubled plan is replanned without an active goal");
        assertEquals(1, port.appliedFor.size(), "the plan subject does not need a goal");
    }

    @Test
    void aPlanWorthyOrderIsPlannedBeforeTheFirstRequest() {
        gateway.enqueue(text("On it."));
        brain.post(said("build a house and bring iron"));
        assertEquals(1, port.proposed.size(), "the planner is asked before the model");
        assertTrue(port.subjects.get(0) instanceof PlanSubject.OwnerOrder, "the order is the subject");
        assertEquals(1, gateway.requests.size());
        String system = gateway.requests.get(0).system();
        assertTrue(system.contains("plan #5"), system);
        assertTrue(system.contains("do not queue another"), system);
    }

    @Test
    void aSimpleOrderDoesNotConsultThePlanner() {
        gateway.enqueue(calls("", call("c1", "mine_blocks")));
        brain.post(said("mine some iron"));
        assertTrue(port.proposed.isEmpty());
        assertTrue(port.failures.isEmpty());
    }

    @Test
    void aFailedPlannerFallsBackToTheModel() {
        port.answer = CompletableFuture.failedFuture(
                new PlannerException(new PlanFailure(PlanFailure.Code.REQUEST_FAILED, "TIMEOUT", 1)));
        gateway.enqueue(text("I will do it step by step."));
        brain.post(said("build a house and bring iron"));
        assertEquals(1, gateway.requests.size());
        String system = gateway.requests.get(0).system();
        assertTrue(system.contains("no plan was drafted (REQUEST_FAILED)"), system);
        assertTrue(system.contains("plan the next step yourself"), system);
    }

    @Test
    void toolSetShrinkAtApplyFallsBackToTheModel() {
        port.applyResult = ToolResult.error("access was revoked after the proposal");
        gateway.enqueue(text("I will plan it myself."));
        brain.post(said("build a house and bring iron"));
        String system = gateway.requests.get(0).system();
        assertTrue(system.contains("could not be queued"), system);
        assertTrue(system.contains("plan it yourself"), system);
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
