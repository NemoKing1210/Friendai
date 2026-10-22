package dev.friedai.brain;

import static dev.friedai.testutil.FakeGateway.call;
import static dev.friedai.testutil.FakeGateway.calls;
import static dev.friedai.testutil.FakeGateway.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friedai.llm.model.LlmException;
import dev.friedai.llm.model.LlmErrorKind;
import dev.friedai.llm.model.LlmResponse;
import dev.friedai.llm.model.Message;
import dev.friedai.llm.model.Role;
import dev.friedai.testutil.FakeGateway;
import dev.friedai.testutil.FakeToolExecutor;
import dev.friedai.testutil.ManualScheduler;
import dev.friedai.testutil.RecordingSpeaker;
import dev.friedai.text.UserMessage;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompanionBrainTest {
    private FakeGateway gateway;
    private FakeToolExecutor tools;
    private RecordingSpeaker speaker;
    private ManualScheduler scheduler;
    private int maxSteps;
    private long cooldown;
    private String perceptionText;
    private Supplier<LlmGateway> gatewaySupplier;
    private CompanionBrain brain;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        tools = new FakeToolExecutor();
        speaker = new RecordingSpeaker();
        scheduler = new ManualScheduler();
        maxSteps = 8;
        cooldown = 0;
        perceptionText = "state-1";
        gatewaySupplier = () -> gateway;
        brain = new CompanionBrain(
                () -> new Persona("Friedai", "friendly", "auto"),
                () -> new BrainSettings(maxSteps, 30, cooldown),
                () -> gatewaySupplier.get(),
                tools,
                () -> perceptionText,
                speaker,
                scheduler);
    }

    private static BrainEvent said(String text) {
        return new BrainEvent.PlayerSaid("Steve", text, BrainEvent.Source.CHAT);
    }

    private static Message lastUser(FakeGateway.Request r) {
        for (int i = r.messages().size() - 1; i >= 0; i--) {
            if (r.messages().get(i).role() == Role.USER) {
                return r.messages().get(i);
            }
        }
        throw new AssertionError("no user message");
    }

    @Test
    void textReplyIsSpokenAndStored() {
        gateway.enqueue(text("Hi"));
        brain.post(said("hi"));
        assertEquals(List.of("Hi"), speaker.said);
        assertEquals(List.of(true, false), speaker.thinkingLog);
        brain.post(said("again"));
        gateway.enqueue(text("Again"));
        List<Message> second = gateway.requests.get(gateway.requests.size() - 1).messages();
        assertEquals("[Steve]: hi", second.get(0).text());
        assertEquals(Role.ASSISTANT, second.get(1).role());
    }

    @Test
    void toolCallLoopsUntilText() {
        gateway.enqueue(calls("", call("c1", "stay")));
        gateway.enqueue(text("Staying"));
        brain.post(said("stay"));
        assertEquals(List.of("stay"), tools.executed);
        assertEquals(2, gateway.requests.size());
        Message toolMsg = gateway.requests.get(1).messages().stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertEquals("c1", toolMsg.toolCallId());
        assertEquals(List.of("Staying"), speaker.said);
    }

    @Test
    void executesAllToolCallsInOrder() {
        gateway.enqueue(calls("", call("a", "first"), call("b", "second")));
        gateway.enqueue(text("ok"));
        brain.post(said("go"));
        assertEquals(List.of("first", "second"), tools.executed);
        List<Message> second = gateway.requests.get(1).messages();
        List<String> toolIds = second.stream().filter(m -> m.role() == Role.TOOL).map(Message::toolCallId).toList();
        assertEquals(List.of("a", "b"), toolIds);
    }

    @Test
    void stopsAtMaxToolSteps() {
        maxSteps = 3;
        for (int i = 0; i < 10; i++) {
            gateway.enqueue(calls("", call("c" + i, "stay")));
        }
        brain.post(said("loop"));
        assertEquals(3, gateway.requests.size());
        assertFalse(brain.isThinking());
    }

    @Test
    void eventsDuringThinkingAreBatched() {
        CompletableFuture<LlmResponse> first = gateway.enqueuePending();
        gateway.enqueue(text("both"));
        brain.post(said("one"));
        brain.post(said("two"));
        brain.post(new BrainEvent.TaskFinished(1, "mine_blocks", true, "done"));
        assertEquals(1, gateway.requests.size());
        first.complete(text("first"));
        assertEquals(2, gateway.requests.size());
        assertEquals("[Steve]: two\n[event] task #1 mine_blocks finished: done", lastUser(gateway.requests.get(1)).text());
    }

    @Test
    void lateResponseAfterCancelIsIgnored() {
        CompletableFuture<LlmResponse> pending = gateway.enqueuePending();
        brain.post(said("hi"));
        brain.cancel();
        pending.complete(calls("late speech", call("c1", "stay")));
        assertTrue(speaker.said.isEmpty());
        assertTrue(tools.executed.isEmpty());
        assertFalse(brain.isThinking());
    }

    @Test
    void errorIsReportedAndBrainRecovers() {
        gateway.enqueueFailure(new LlmException(LlmErrorKind.AUTH, "bad key"));
        brain.post(said("hi"));
        assertEquals(List.of(UserMessage.of("friedai.error.llm.auth")), speaker.errors);
        gateway.enqueue(text("back"));
        brain.post(said("again"));
        assertEquals(List.of("back"), speaker.said);
    }

    @Test
    void cooldownDelaysNextTurn() {
        cooldown = 500;
        gateway.enqueue(text("one"));
        gateway.enqueue(text("two"));
        brain.post(said("a"));
        brain.post(said("b"));
        assertEquals(1, gateway.requests.size());
        scheduler.advance(499);
        assertEquals(1, gateway.requests.size());
        scheduler.advance(1);
        assertEquals(2, gateway.requests.size());
    }

    @Test
    void systemPromptHasFreshPerception() {
        gateway.enqueue(text("a"));
        gateway.enqueue(text("b"));
        brain.post(said("1"));
        perceptionText = "state-2";
        brain.post(said("2"));
        assertTrue(gateway.requests.get(0).system().contains("state-1"));
        assertTrue(gateway.requests.get(1).system().contains("state-2"));
    }

    @Test
    void gatewaySupplierReadEachTurn() {
        FakeGateway other = new FakeGateway();
        gateway.enqueue(text("a"));
        other.enqueue(text("b"));
        brain.post(said("1"));
        gatewaySupplier = () -> other;
        brain.post(said("2"));
        assertEquals(1, gateway.requests.size());
        assertEquals(1, other.requests.size());
    }
}
