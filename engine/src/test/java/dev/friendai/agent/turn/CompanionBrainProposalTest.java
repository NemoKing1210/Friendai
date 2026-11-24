package dev.friendai.agent.turn;

import static dev.friendai.testutil.FakeGateway.calls;
import static dev.friendai.testutil.FakeGateway.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.plan.PlanProposal;
import dev.friendai.agent.plan.PlanSubject;
import dev.friendai.agent.plan.PlannerInput;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.testutil.FakeGateway;
import dev.friendai.testutil.FakeToolExecutor;
import dev.friendai.testutil.ManualScheduler;
import dev.friendai.testutil.RecordingSpeaker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The owner asks to see a plan before any work starts. The planner's proposal is validated and presented, never
 * queued; the turn may only look. The plan starts when the owner approves it, and a changed request drops it.
 */
class CompanionBrainProposalTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PROPOSE_ONLY = "Make a plan to build a house. Do nothing yet.";
    /** An exact approval that also names planning work, so the turn must not plan the order a second time. */
    private static final String APPROVE = "выполняй план";

    /** Answers with a fixed proposal and records every call the brain makes. */
    private static final class FakePort implements PlannerPort {
        final List<PlanSubject> proposed = new ArrayList<>();
        final List<PlanSubject> applied = new ArrayList<>();
        final List<PlanSubject> validated = new ArrayList<>();
        ToolResult validateResult = ToolResult.ok("Valid plan (worst-case actions: 1)");
        ToolResult applyResult = ToolResult.ok("plan #9 added: 1 jobs");
        CompletableFuture<PlanProposal> answer = CompletableFuture.completedFuture(proposal());

        @Override
        public Optional<PlannerInput> inputFor(PlanSubject subject, Caller caller, String failure) {
            Goal goal = new Goal(1, "house", List.of(), GoalStatus.ACTIVE, 0, 0, List.of(), 0, false);
            return Optional.of(PlannerInput.promptOnly(goal, "w", "t", "", Optional.empty(), List.of(), failure));
        }

        @Override
        public CompletableFuture<PlanProposal> propose(PlannerInput input) {
            proposed.add(
                    input.targetPlanId() > 0
                            ? null
                            : new PlanSubject.OwnerOrder(input.goal().text()));
            return answer;
        }

        @Override
        public ToolResult validate(Caller caller, PlanSubject subject, PlanProposal proposal) {
            validated.add(subject);
            return validateResult;
        }

        @Override
        public ToolResult apply(Caller caller, PlanSubject subject, PlanProposal proposal) {
            applied.add(subject);
            return applyResult;
        }
    }

    private static PlanProposal proposal() {
        PlanDefinition plan = new PlanDefinition(List.of(new PlanNode.Action(new QueuedStep(
                "mine_blocks", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of()))));
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
        tools = new FakeToolExecutor().offers("queue_add", "set_goal", "mine_blocks", "stay");
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
    void aProposalIsShownAndNothingIsQueued() {
        gateway.enqueue(text("Here is the plan: 1) mine stone."));

        brain.post(said(PROPOSE_ONLY));

        assertEquals(1, port.validated.size(), "the plan is checked before it is shown");
        assertTrue(port.applied.isEmpty(), "a plan the owner has not approved must not be queued");
        assertEquals(1, gateway.requests.size());
        String system = gateway.requests.get(0).system();
        assertTrue(system.contains("NOT queued"), system);
        assertTrue(system.contains("Do not queue, run or start anything"), system);
        assertEquals(List.of("Here is the plan: 1) mine stone."), speaker.said);
    }

    @Test
    void theProposalTurnOffersOnlyReadOnlyTools() {
        gateway.enqueue(calls("Planning.", FakeGateway.call("q1", "queue_add"), FakeGateway.call("s1", "set_goal")));

        brain.post(said(PROPOSE_ONLY));

        List<ToolSpec> offered = gateway.requests.get(0).tools();
        assertTrue(offered.stream().noneMatch(spec -> spec.name().equals("queue_add")), "no queueing");
        assertTrue(offered.stream().noneMatch(spec -> spec.name().equals("set_goal")), "no goal change");
        assertTrue(
                offered.stream().allMatch(spec -> readOnly(spec.name())),
                "only observation: " + offered.stream().map(ToolSpec::name).toList());
    }

    @Test
    void aModelThatTriesToQueueAnywayIsRefusedWithoutEffect() {
        gateway.enqueue(calls("", FakeGateway.call("q1", "queue_add")));
        gateway.enqueue(text("I will only describe it."));

        brain.post(said(PROPOSE_ONLY));

        assertTrue(tools.executed.isEmpty(), "no tool may run on a proposal turn, not even a refused one");
        assertEquals(2, gateway.requests.size(), "the refusal reaches the model as an ordinary tool result");
        assertEquals(
                1,
                gateway.requests.get(1).messages().stream()
                        .filter(message -> message.text().contains("not offered"))
                        .count());
        assertTrue(port.applied.isEmpty());
    }

    @Test
    void anApprovedPlanIsQueuedOnceAndNotReplaced() {
        gateway.enqueue(text("Here is the plan."));
        brain.post(said(PROPOSE_ONLY));

        gateway.enqueue(text("Started."));
        brain.post(said(APPROVE));

        assertEquals(1, port.applied.size(), "the approved plan is queued exactly once");
        assertTrue(port.applied.get(0) instanceof PlanSubject.OwnerOrder);
        assertEquals(2, gateway.requests.size(), "the approval is answered, not planned a second time");
        String system = gateway.requests.get(1).system();
        assertTrue(system.contains("The owner approved the plan"), system);
        assertTrue(system.contains("do not queue another plan"), system);
    }

    @Test
    void aChangedRequestDropsTheProposalInsteadOfApprovingIt() {
        gateway.enqueue(text("Here is the plan."));
        brain.post(said(PROPOSE_ONLY));

        gateway.enqueue(calls("", FakeGateway.call("q1", "queue_add")));
        brain.post(said("build a house and bring iron"));

        assertEquals(1, port.applied.size(), "the new request gets its own plan, and only that one is queued");
        assertTrue(
                port.applied.get(0) instanceof PlanSubject.OwnerOrder order
                        && order.text().contains("bring iron"),
                "the queued plan belongs to the new request, never to the dropped proposal");
        assertEquals(1, port.validated.size(), "the dropped proposal is not validated again");
    }

    @Test
    void aFailedValidationShowsNoPlanAndSaysWhy() {
        port.validateResult = ToolResult.error("Invalid plan:\n- mine_blocks needs a count");

        gateway.enqueue(text("I could not shape it."));
        brain.post(said(PROPOSE_ONLY));

        assertTrue(port.applied.isEmpty());
        String system = gateway.requests.get(0).system();
        assertTrue(system.contains("did not pass validation"), system);
        assertTrue(system.contains("mine_blocks needs a count"), system);
    }

    @Test
    void aPlanTheQueueRefusesAtApprovalIsReportedNotStarted() {
        gateway.enqueue(text("Here is the plan."));
        brain.post(said(PROPOSE_ONLY));
        port.applyResult = ToolResult.error("Invalid plan: with the companion's current state every condition skips");

        gateway.enqueue(text("It cannot start."));
        brain.post(said(APPROVE));

        String system = gateway.requests.get(1).system();
        assertTrue(system.contains("could not be queued"), system);
        assertTrue(system.contains("every condition skips"), system);
        assertEquals(2, gateway.requests.size());
    }

    @Test
    void aStopWhileWaitingForTheOwnerForgetsTheProposal() {
        gateway.enqueue(text("Here is the plan."));
        brain.post(said(PROPOSE_ONLY));

        brain.cancel();
        gateway.enqueue(text("There is no plan to start."));
        brain.post(said("go ahead"));

        assertTrue(port.applied.isEmpty(), "a cancelled turn leaves no plan to approve");
        assertFalse(gateway.requests.get(1).system().contains("The owner approved the plan"));
    }

    @Test
    void aPlannerWithoutAProposalQueuesItLikeBefore() {
        gateway.enqueue(calls("", FakeGateway.call("q1", "queue_add"), FakeGateway.call("s1", "set_goal")));
        gateway.enqueue(text("Working on it."));

        brain.post(said("build a house and bring iron"));

        assertEquals(1, port.applied.size(), "an ordinary plan-worthy order still queues its plan");
        assertTrue(port.validated.isEmpty(), "no separate validation for a queued plan");
        assertEquals(2, gateway.requests.size(), "the turn answers after the refusal");
        assertNotNull(speaker.said);
    }

    /** The groups a proposal turn may offer: observation, never work. */
    private static boolean readOnly(String tool) {
        return switch (tool) {
            case "stay", "mine_blocks" -> false;
            default -> true;
        };
    }

    private static BrainEvent said(String text) {
        return new BrainEvent.PlayerSaid(OWNER, "Steve", text, BrainEvent.Source.CHAT);
    }
}
