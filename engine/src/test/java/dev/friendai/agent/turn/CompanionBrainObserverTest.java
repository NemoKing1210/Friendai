package dev.friendai.agent.turn;

import static dev.friendai.testutil.FakeGateway.call;
import static dev.friendai.testutil.FakeGateway.calls;
import static dev.friendai.testutil.FakeGateway.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.testutil.FakeGateway;
import dev.friendai.testutil.FakeToolExecutor;
import dev.friendai.testutil.ManualScheduler;
import dev.friendai.testutil.RecordingSpeaker;
import dev.friendai.execution.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompanionBrainObserverTest {
    private static final class Recorder implements BrainObserver {
        final List<String> calls = new ArrayList<>();

        @Override
        public void onEvent(BrainEvent e) {
            calls.add("event:" + e.toPromptLine());
        }

        @Override
        public void onEventDropped(BrainEvent e, String reason) {
            calls.add("dropped:" + reason);
        }

        @Override
        public void onAssistantText(String text) {
            calls.add("text:" + text);
        }

        @Override
        public void onToolCall(ToolCall call) {
            calls.add("call:" + call.name());
        }

        @Override
        public void onToolResult(ToolCall call, ToolResult result) {
            calls.add("result:" + call.name() + ":" + result.content());
        }

        @Override
        public void onError(Throwable error) {
            calls.add("error:" + error.getMessage());
        }
    }

    private FakeGateway gateway;
    private FakeToolExecutor tools;
    private ManualScheduler scheduler;
    private Recorder recorder;
    private CompanionBrain brain;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        tools = new FakeToolExecutor();
        scheduler = new ManualScheduler();
        recorder = new Recorder();
        brain = new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(8, 30, 0, 5_000),
                () -> gateway,
                tools,
                () -> "state-1",
                new RecordingSpeaker(),
                scheduler,
                AccessResolver.of(UUID.fromString("00000000-0000-0000-0000-000000000001"), p -> false));
        brain.setObserver(recorder);
    }

    private static BrainEvent said(String text) {
        return new BrainEvent.PlayerSaid(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), "Steve", text, BrainEvent.Source.CHAT);
    }

    @Test
    void reportsEventsInOrder() {
        gateway.enqueuePending();
        brain.post(said("one"));
        brain.post(said("two"));
        assertEquals(List.of("event:[Steve]: one"), recorder.calls);
    }

    @Test
    void reportsDroppedGuestMessage() {
        gateway.enqueue(text("hi"));
        UUID guest = UUID.fromString("00000000-0000-0000-0000-000000000002");
        brain.post(new BrainEvent.PlayerSaid(guest, "Bob", "a", BrainEvent.Source.CHAT));
        brain.post(new BrainEvent.PlayerSaid(guest, "Bob", "b", BrainEvent.Source.CHAT));
        assertTrue(recorder.calls.contains("dropped:guest cooldown"));
    }

    @Test
    void reportsAssistantTextAndToolRoundTrip() {
        gateway.enqueue(calls("thinking", call("c1", "stay")));
        gateway.enqueue(text("done"));
        brain.post(said("stay"));
        assertEquals(
                List.of("event:[Steve]: stay", "text:thinking", "call:stay", "result:stay:ok stay", "text:done"),
                recorder.calls);
    }

    @Test
    void reportsErrorOnGatewayFailure() {
        gateway.enqueueFailure(new IllegalStateException("boom"));
        brain.post(said("hi"));
        assertEquals(
                1, recorder.calls.stream().filter(c -> c.startsWith("error:")).count());
        assertTrue(recorder.calls.contains("error:boom"));
    }

    @Test
    void silentAfterCancel() {
        var pending = gateway.enqueuePending();
        brain.post(said("hi"));
        brain.cancel();
        int before = recorder.calls.size();
        pending.complete(text("late"));
        assertEquals(before, recorder.calls.size());
    }

    @Test
    void exposesCurrentSystemPrompt() {
        String prompt = brain.currentSystemPrompt();
        assertTrue(prompt.contains("Friendai"));
        assertTrue(prompt.contains("state-1"));
    }

    @Test
    void observerExceptionDoesNotBreakTurn() {
        brain.setObserver(new BrainObserver() {
            @Override
            public void onAssistantText(String text) {
                throw new IllegalStateException("observer bug");
            }
        });
        gateway.enqueue(text("Hi"));
        brain.post(said("hi"));
        assertTrue(!brain.isThinking());
        gateway.enqueue(text("Again"));
        brain.post(said("again"));
        assertEquals(2, gateway.requests.size());
    }
}
