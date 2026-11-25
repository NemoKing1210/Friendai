package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.Role;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.testutil.FakeGateway;
import dev.friendai.testutil.FakeToolExecutor;
import dev.friendai.testutil.ManualScheduler;
import dev.friendai.testutil.RecordingSpeaker;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** The request budget as the turn loop applies it, not only as the pure function decides it. */
class CompanionBrainBudgetTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** A gateway that knows its own limits and forwards to the scripted fake. */
    private static final class LimitedGateway implements LlmGateway {
        final FakeGateway delegate = new FakeGateway();
        final int context;
        final int output;

        LimitedGateway(int context, int output) {
            this.context = context;
            this.output = output;
        }

        @Override
        public int contextTokens() {
            return context;
        }

        @Override
        public int maxOutputTokens() {
            return output;
        }

        @Override
        public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
            return delegate.complete(system, messages, tools);
        }
    }

    private final RecordingSpeaker speaker = new RecordingSpeaker();

    private CompanionBrain brain(LlmGateway gateway) {
        return new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(8, 30, 0, 0),
                () -> gateway,
                new FakeToolExecutor(),
                () -> "state",
                speaker,
                new ManualScheduler(),
                AccessResolver.of(OWNER, new HashSet<>()::contains));
    }

    private static BrainEvent said(String text) {
        return new BrainEvent.PlayerSaid(OWNER, "Steve", text, BrainEvent.Source.CHAT);
    }

    @Test
    void aLongHistoryIsCompactedAndStillEndsWithTheCurrentLine() {
        LimitedGateway gateway = new LimitedGateway(20_000, 1_000);
        CompanionBrain brain = brain(gateway);
        for (int turn = 1; turn <= 6; turn++) {
            gateway.delegate.enqueue(FakeGateway.text("reply " + turn + " " + "r".repeat(16_000)));
        }

        for (int turn = 1; turn <= 6; turn++) {
            brain.post(said("question " + turn));
        }

        assertEquals(6, gateway.delegate.requests.size(), "every turn still reaches the provider");
        List<Message> last = gateway.delegate.requests.getLast().messages();
        assertEquals(Role.USER, last.getFirst().role(), "history starts a whole exchange");
        assertTrue(last.getLast().text().contains("question 6"), "the current line is never dropped");
        assertTrue(
                gateway.delegate.requests.stream()
                        .noneMatch(request -> request.messages().stream()
                                .anyMatch(message -> message.text().startsWith("question 1"))),
                "the oldest exchange was compacted away by the last request");
    }

    @Test
    void aRequestThatCannotFitIsRefusedBeforeAnyCall() {
        LimitedGateway gateway = new LimitedGateway(3_000, 1_000);
        CompanionBrain brain = brain(gateway);
        List<String> direct = new java.util.ArrayList<>();
        brain.setObserver(new BrainObserver() {
            @Override
            public void onDirectLine(String key) {
                direct.add(key);
            }
        });

        brain.post(said("x".repeat(20_000)));

        assertTrue(gateway.delegate.requests.isEmpty(), "nothing is sent");
        assertEquals(1, speaker.errors.size(), speaker.errors.toString());
        UserMessage error = speaker.errors.getFirst();
        assertEquals("friendai.agent.request_too_large", error.key());
        assertEquals(2, error.args().size(), "the estimate and the budget are named");
        assertEquals(List.of("friendai.agent.request_too_large"), direct, "the owner heard this line");
        assertFalse(brain.isThinking());
        assertFalse(brain.hasPendingWork());
    }
}
