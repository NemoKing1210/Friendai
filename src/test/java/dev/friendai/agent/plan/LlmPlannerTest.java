package dev.friendai.agent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.testutil.FakeGateway;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class LlmPlannerTest {
    private static final JsonObject SCHEMA = JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}").getAsJsonObject();
    private static final String GOOD = "{\"rationale\":\"r\",\"steps\":[{\"tool\":\"jump\"}]}";

    private final FakeGateway gateway = new FakeGateway();
    private final LlmPlanner planner = new LlmPlanner(() -> gateway, text -> text, 30, 12, 8192, 49152);

    private static PlannerInput input() {
        Goal goal = new Goal(1, "hop", List.of(), GoalStatus.ACTIVE, 0, 0, List.of(), 0, false);
        return new PlannerInput(goal, "w", "t", "", Optional.empty(), List.of(), "", Map.of("jump", SCHEMA), Set.of("jump"));
    }

    @Test
    void validReplyReturnsProposal() throws Exception {
        gateway.enqueue(FakeGateway.text(GOOD));
        PlanProposal proposal = planner.propose(input()).get();
        assertEquals("r", proposal.rationale());
        assertEquals(1, gateway.requests.size());
    }

    @Test
    void requestHasNoToolsAttached() throws Exception {
        gateway.enqueue(FakeGateway.text(GOOD));
        planner.propose(input()).get();
        assertTrue(gateway.requests.get(0).tools().isEmpty());
        assertTrue(gateway.requests.get(0).system().contains("one JSON object"));
    }

    @Test
    void invalidThenValidReplyRetriesOnceWithTheErrors() throws Exception {
        gateway.enqueue(FakeGateway.text("{\"steps\":[{\"tool\":\"teleport\"}]}"));
        gateway.enqueue(FakeGateway.text(GOOD));
        planner.propose(input()).get();
        assertEquals(2, gateway.requests.size());
        String secondUser = gateway.requests.get(1).messages().get(0).text();
        assertTrue(secondUser.contains("unknown tool 'teleport'"), secondUser);
    }

    @Test
    void twoInvalidRepliesFail() {
        gateway.enqueue(FakeGateway.text("nope"));
        gateway.enqueue(FakeGateway.text("still nope"));
        ExecutionException e = assertThrows(ExecutionException.class, () -> planner.propose(input()).get());
        assertTrue(e.getCause() instanceof PlannerException, String.valueOf(e.getCause()));
        assertEquals(2, gateway.requests.size());
    }

    @Test
    void gatewayFailurePropagatesWithoutDetails() {
        gateway.enqueueFailure(new IllegalStateException("401 key sk-secret-1"));
        ExecutionException e = assertThrows(ExecutionException.class, () -> planner.propose(input()).get());
        assertTrue(e.getCause() instanceof PlannerException);
        assertTrue(!e.getCause().getMessage().contains("sk-secret-1"), "the message must not echo gateway details");
    }

    @Test
    void aThrowingGatewayIsAFailureNotACrash() {
        LlmPlanner broken = new LlmPlanner(() -> {
            throw new IllegalStateException("no gateway");
        }, text -> text, 30, 12, 8192, 49152);
        ExecutionException e = assertThrows(ExecutionException.class, () -> broken.propose(input()).get());
        assertTrue(e.getCause() instanceof PlannerException);
    }

    @Test
    void aReplyThatNeverArrivesTimesOut() {
        CompletableFuture<?> pending = gateway.enqueuePending();
        LlmPlanner quick = new LlmPlanner(() -> gateway, text -> text, 1, 12, 8192, 49152);
        ExecutionException e = assertThrows(ExecutionException.class, () -> quick.propose(input()).get());
        assertTrue(e.getCause() instanceof PlannerException, String.valueOf(e.getCause()));
        pending.cancel(true);
    }
}
