package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.llm.stream.StreamDelta;
import dev.friendai.agent.llm.stream.StreamingLlmGateway;
import dev.friendai.testutil.FakeToolExecutor;
import dev.friendai.testutil.ManualScheduler;
import dev.friendai.testutil.RecordingSpeaker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class CompanionBrainStreamTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private record Step(List<StreamDelta> deltas, LlmResponse response) {}

    private static final class StreamingGateway implements StreamingLlmGateway {
        final Queue<Step> steps = new ArrayDeque<>();
        final List<String> order = new ArrayList<>();

        @Override
        public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
            throw new UnsupportedOperationException("streaming only");
        }

        @Override
        public CompletableFuture<LlmResponse> completeStreaming(
                String system, List<Message> messages, List<ToolSpec> tools, Consumer<StreamDelta> onDelta) {
            Step step = steps.poll();
            if (step == null) return CompletableFuture.failedFuture(new IllegalStateException("no step"));
            step.deltas().forEach(onDelta);
            order.add("complete");
            return CompletableFuture.completedFuture(step.response());
        }
    }

    private static CompanionBrain brain(StreamingLlmGateway gateway, FakeToolExecutor tools, RecordingSpeaker speaker) {
        return new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(8, 30, 0, 0),
                () -> gateway,
                tools,
                () -> "state",
                speaker,
                new ManualScheduler(),
                AccessResolver.of(OWNER, new HashSet<>()::contains));
    }

    @Test
    void anEmptyEnvelopeIsReconstructedFromTextIncrements() {
        StreamingGateway gateway = new StreamingGateway();
        gateway.steps.add(new Step(
                List.of(new StreamDelta.Text("Hel"), new StreamDelta.Text("lo"), new StreamDelta.Done()),
                new LlmResponse("", List.of(), StopReason.END, new Usage(1, 2))));
        FakeToolExecutor tools = new FakeToolExecutor();
        RecordingSpeaker speaker = new RecordingSpeaker();

        brain(gateway, tools, speaker).post(new BrainEvent.PlayerSaid(OWNER, "Steve", "hi", BrainEvent.Source.CHAT));

        assertTrue(speaker.said.stream().anyMatch(line -> line.contains("Hello")), speaker.said.toString());
        assertTrue(gateway.order.contains("complete"));
        assertTrue(tools.executed.isEmpty(), "text increments never execute a tool");
    }

    @Test
    void aCancelledStreamCannotSpeakOrExecute() {
        CompletableFuture<LlmResponse> pending = new CompletableFuture<>();
        StreamingLlmGateway gateway = new StreamingLlmGateway() {
            @Override
            public CompletableFuture<LlmResponse> complete(
                    String system, List<Message> messages, List<ToolSpec> tools) {
                throw new UnsupportedOperationException("streaming only");
            }

            @Override
            public CompletableFuture<LlmResponse> completeStreaming(
                    String system, List<Message> messages, List<ToolSpec> tools, Consumer<StreamDelta> onDelta) {
                onDelta.accept(new StreamDelta.Text("Late."));
                onDelta.accept(new StreamDelta.ToolCall(0, "c", "mine", "{}"));
                return pending;
            }
        };
        FakeToolExecutor tools = new FakeToolExecutor();
        RecordingSpeaker speaker = new RecordingSpeaker();
        CompanionBrain brain = brain(gateway, tools, speaker);

        brain.post(new BrainEvent.PlayerSaid(OWNER, "Steve", "mine", BrainEvent.Source.CHAT));
        brain.cancel();
        pending.complete(new LlmResponse("", List.of(), StopReason.END, null));

        assertTrue(speaker.said.isEmpty(), speaker.said.toString());
        assertTrue(tools.executed.isEmpty(), "a cancelled stream has no effect");
    }

    @Test
    void toolFragmentsExecuteOnceOnlyAfterTheCompleteResponse() {
        StreamingGateway gateway = new StreamingGateway();
        gateway.steps.add(new Step(
                List.of(
                        new StreamDelta.ToolCall(0, "c1", "mine", "{\"block\""),
                        new StreamDelta.ToolCall(0, "", "", ":\"stone\"}"),
                        new StreamDelta.Done()),
                new LlmResponse("", List.of(), StopReason.END, new Usage(1, 2))));
        gateway.steps.add(new Step(
                List.of(new StreamDelta.Text("Done.")),
                new LlmResponse("Done.", List.of(), StopReason.END, new Usage(1, 1))));
        FakeToolExecutor tools = new FakeToolExecutor();
        RecordingSpeaker speaker = new RecordingSpeaker();

        brain(gateway, tools, speaker)
                .post(new BrainEvent.PlayerSaid(OWNER, "Steve", "mine stone", BrainEvent.Source.CHAT));

        assertEquals(List.of("mine"), tools.executed);
        assertTrue(gateway.order.contains("complete"));
    }
}
