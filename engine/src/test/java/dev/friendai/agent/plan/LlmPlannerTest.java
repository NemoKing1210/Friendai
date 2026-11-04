package dev.friendai.agent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.agent.llm.model.LlmErrorKind;
import dev.friendai.agent.llm.model.LlmException;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.testutil.FakeGateway;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LlmPlannerTest {
    private static final JsonObject SCHEMA =
            JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}").getAsJsonObject();
    private static final String GOOD = "{\"rationale\":\"r\",\"steps\":[{\"tool\":\"jump\"}]}";

    /** A JSON-only gateway that can be scripted with failures before the replies, like a flaky provider. */
    private static final class ScriptedGateway implements LlmGateway {
        final Queue<CompletableFuture<LlmResponse>> queue = new ArrayDeque<>();
        final List<List<Message>> jsonRequests = new ArrayList<>();

        void failNext(Throwable error) {
            queue.add(CompletableFuture.failedFuture(error));
        }

        void enqueue(String json) {
            queue.add(CompletableFuture.completedFuture(FakeGateway.text(json)));
        }

        @Override
        public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
            throw new UnsupportedOperationException("the planner only asks for JSON");
        }

        @Override
        public CompletableFuture<LlmResponse> completeJson(
                String system, List<Message> messages, int outputMultiplier) {
            jsonRequests.add(new ArrayList<>(messages));
            CompletableFuture<LlmResponse> next = queue.poll();
            return next == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("no response queued"))
                    : next;
        }
    }

    private final FakeGateway gateway = new FakeGateway();
    private final LlmPlanner planner = new LlmPlanner(() -> gateway, text -> text, 30, 12, 8192, 49152);

    private static PlannerInput input() {
        Goal goal = new Goal(1, "hop", List.of(), GoalStatus.ACTIVE, 0, 0, List.of(), 0, false);
        return new PlannerInput(
                goal, "w", "t", "", Optional.empty(), List.of(), "", Map.of("jump", SCHEMA), Set.of("jump"));
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
        String secondUser = gateway.requests.get(1).messages().getLast().text();
        assertTrue(secondUser.contains("unknown tool 'teleport'"), secondUser);
    }

    @Test
    void twoInvalidRepliesFail() {
        gateway.enqueue(FakeGateway.text("nope"));
        gateway.enqueue(FakeGateway.text("still nope"));
        ExecutionException e = assertThrows(
                ExecutionException.class, () -> planner.propose(input()).get());
        assertTrue(e.getCause() instanceof PlannerException, String.valueOf(e.getCause()));
        assertEquals(2, gateway.requests.size());
    }

    @Test
    void gatewayFailurePropagatesWithoutDetails() {
        gateway.enqueueFailure(new IllegalStateException("401 key sk-secret-1"));
        ExecutionException e = assertThrows(
                ExecutionException.class, () -> planner.propose(input()).get());
        assertTrue(e.getCause() instanceof PlannerException);
        assertTrue(!e.getCause().getMessage().contains("sk-secret-1"), "the message must not echo gateway details");
    }

    @Test
    void aThrowingGatewayIsAFailureNotACrash() {
        LlmPlanner broken = new LlmPlanner(
                () -> {
                    throw new IllegalStateException("no gateway");
                },
                text -> text,
                30,
                12,
                8192,
                49152);
        ExecutionException e = assertThrows(
                ExecutionException.class, () -> broken.propose(input()).get());
        assertTrue(e.getCause() instanceof PlannerException);
    }

    @Test
    void aReplyThatNeverArrivesTimesOut() {
        CompletableFuture<?> pending = gateway.enqueuePending();
        LlmPlanner quick = new LlmPlanner(() -> gateway, text -> text, 1, 12, 8192, 49152);
        ExecutionException e = assertThrows(
                ExecutionException.class, () -> quick.propose(input()).get());
        assertTrue(e.getCause() instanceof PlannerException, String.valueOf(e.getCause()));
        pending.cancel(true);
    }

    @Test
    void aRetryableTransportFailureRetriesTheSameRequest() throws Exception {
        ScriptedGateway flaky = new ScriptedGateway();
        flaky.failNext(new LlmException(LlmErrorKind.TIMEOUT, "timed out"));
        flaky.enqueue(GOOD);
        List<LlmPlanner.Event> events = new ArrayList<>();
        LlmPlanner retrying = new LlmPlanner(() -> flaky, text -> text, 30, 12, 8192, 49152, 2, events::add);

        PlanProposal proposal = retrying.propose(input()).get(5, TimeUnit.SECONDS);

        assertEquals("r", proposal.rationale());
        assertEquals(2, flaky.jsonRequests.size(), "the failed request is sent once more");
        assertEquals(
                flaky.jsonRequests.get(0).size(),
                flaky.jsonRequests.get(1).size(),
                "a transport retry repeats the request instead of adding a repair turn");
        assertEquals(
                1,
                events.stream()
                        .filter(event -> "transport-retry".equals(event.disposition()))
                        .count());
    }

    @Test
    void anAuthFailureIsNotRetried() {
        ScriptedGateway flaky = new ScriptedGateway();
        flaky.failNext(new LlmException(LlmErrorKind.AUTH, "bad key"));
        LlmPlanner retrying = new LlmPlanner(() -> flaky, text -> text, 30, 12, 8192, 49152);

        ExecutionException error = assertThrows(
                ExecutionException.class, () -> retrying.propose(input()).get(5, TimeUnit.SECONDS));

        assertTrue(error.getCause() instanceof PlannerException, String.valueOf(error.getCause()));
        assertEquals(1, flaky.jsonRequests.size(), "an auth failure is the answer, not a transient fault");
    }

    @Test
    void aCancelledTurnSchedulesNoFurtherAttempt() {
        ScriptedGateway flaky = new ScriptedGateway();
        flaky.failNext(new LlmException(LlmErrorKind.TIMEOUT, "timed out"));
        flaky.enqueue(GOOD);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        LlmPlanner retrying = new LlmPlanner(() -> flaky, text -> text, 30, 12, 8192, 49152);

        CompletableFuture<PlanProposal> future = retrying.propose(input(), cancelled::get);
        cancelled.set(true);

        ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof PlannerException, String.valueOf(error.getCause()));
        assertEquals(1, flaky.jsonRequests.size(), "a stopped turn sends nothing more");
    }
}
