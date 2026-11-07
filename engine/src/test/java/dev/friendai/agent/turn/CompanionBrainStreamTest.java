package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.model.LlmErrorKind;
import dev.friendai.agent.llm.model.LlmException;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.llm.stream.SpeechStreamBuffer;
import dev.friendai.agent.llm.stream.StreamDelta;
import dev.friendai.agent.llm.stream.StreamingLlmGateway;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.testutil.FakeGateway;
import dev.friendai.testutil.FakeToolExecutor;
import dev.friendai.testutil.ManualScheduler;
import dev.friendai.testutil.RecordingSpeaker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
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

    private static CompanionBrain brain(LlmGateway gateway, FakeToolExecutor tools, Speaker speaker) {
        return brain(gateway, tools, speaker, new ManualScheduler());
    }

    private static CompanionBrain brain(
            LlmGateway gateway, FakeToolExecutor tools, Speaker speaker, BrainScheduler scheduler) {
        return new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(8, 30, 0, 0),
                () -> gateway,
                tools,
                () -> "state",
                speaker,
                scheduler,
                AccessResolver.of(OWNER, new HashSet<>()::contains));
    }

    private static BrainEvent said(String text) {
        return new BrainEvent.PlayerSaid(OWNER, "Steve", text, BrainEvent.Source.CHAT);
    }

    /**
     * Records what reached the transport and throws where the test asks it to: a wasted close attempt, a broken chat
     * line, a failing thinking indicator or a failing error line.
     */
    private static final class FlakySpeaker implements Speaker {
        final RecordingSpeaker sent = new RecordingSpeaker();
        final Set<Long> failOnceAt = new HashSet<>();
        final List<String> rejected = new ArrayList<>();
        boolean failAllStreams;
        boolean failSay;
        boolean failThinking;
        boolean failError;
        int streamAttempts;

        @Override
        public void say(String text) {
            if (failSay) throw new IllegalStateException("say failed");
            sent.say(text);
        }

        @Override
        public void say(String text, UUID addressee) {
            if (failSay) throw new IllegalStateException("say failed");
            sent.say(text, addressee);
        }

        @Override
        public void streamSentence(
                long streamId, long sessionId, long sequence, String text, boolean last, UUID addressee) {
            streamAttempts++;
            if (failAllStreams || failOnceAt.remove(sequence)) {
                rejected.add(sequence + ":" + text);
                throw new IllegalStateException("stream failed at sequence " + sequence);
            }
            sent.streamSentence(streamId, sessionId, sequence, text, last, addressee);
        }

        @Override
        public void say(UserMessage message) {
            sent.say(message);
        }

        @Override
        public void thinking(boolean on) {
            if (failThinking) throw new IllegalStateException("thinking failed");
            sent.thinking(on);
        }

        @Override
        public void error(UserMessage message) {
            if (failError) throw new IllegalStateException("error failed");
            sent.errors.add(message);
        }
    }

    /** Holds scheduled work until the test drains it, so a delta can be queued behind a finished response. */
    private static final class QueueingScheduler implements BrainScheduler {
        private final ArrayDeque<Runnable> queued = new ArrayDeque<>();

        @Override
        public void execute(Runnable r) {
            queued.addLast(r);
        }

        @Override
        public void schedule(Runnable r, long delayMs) {
            queued.addLast(r);
        }

        @Override
        public long nowMillis() {
            return 0;
        }

        void drain() {
            while (!queued.isEmpty()) {
                queued.removeFirst().run();
            }
        }
    }

    /** Records each request's delta sink and answers from futures the test completes. */
    private static final class ScriptedStreamingGateway implements StreamingLlmGateway {
        final List<Consumer<StreamDelta>> deltas = new ArrayList<>();
        final Queue<CompletableFuture<LlmResponse>> answers = new ArrayDeque<>();

        @Override
        public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
            throw new UnsupportedOperationException("streaming only");
        }

        @Override
        public CompletableFuture<LlmResponse> completeStreaming(
                String system, List<Message> messages, List<ToolSpec> tools, Consumer<StreamDelta> onDelta) {
            deltas.add(onDelta);
            CompletableFuture<LlmResponse> answer = answers.poll();
            return answer == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("no answer scripted"))
                    : answer;
        }
    }

    private static LlmResponse reply(String text) {
        return new LlmResponse(text, List.of(), StopReason.END, null);
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
    void aCancelledTurnCancelsTheStreamingRequest() {
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
                return pending;
            }
        };
        CompanionBrain brain = brain(gateway, new FakeToolExecutor(), new RecordingSpeaker());

        brain.post(new BrainEvent.PlayerSaid(OWNER, "Steve", "hi", BrainEvent.Source.CHAT));
        brain.cancel();

        assertTrue(pending.isCancelled(), "a stop must release the request it started, not only ignore its answer");
    }

    @Test
    void theFirstSafeSentenceSpeaksBeforeTheStreamCompletes() {
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
                onDelta.accept(new StreamDelta.Text("Hello. There."));
                return pending;
            }
        };
        RecordingSpeaker speaker = new RecordingSpeaker();
        CompanionBrain brain = brain(gateway, new FakeToolExecutor(), speaker);

        brain.post(new BrainEvent.PlayerSaid(OWNER, "Steve", "hi", BrainEvent.Source.CHAT));

        assertEquals(
                "Hello. There.",
                speaker.streamed.stream().map(RecordingSpeaker.Streamed::text).reduce("", String::concat));
        pending.complete(new LlmResponse("Hello. There.", List.of(), StopReason.END, null));
        assertEquals(
                "Hello. There.",
                speaker.streamed.stream().map(RecordingSpeaker.Streamed::text).reduce("", String::concat));
        assertTrue(speaker.streamed.getLast().last());
    }

    @Test
    void streamedSpeechIsOrderedAndTheFullReplyIsRecordedOnce() {
        StreamingGateway gateway = new StreamingGateway();
        gateway.steps.add(new Step(
                List.of(new StreamDelta.Text("Hello there. Second."), new StreamDelta.Done()),
                new LlmResponse("", List.of(), StopReason.END, new Usage(1, 2))));
        FakeToolExecutor tools = new FakeToolExecutor();
        RecordingSpeaker speaker = new RecordingSpeaker();

        brain(gateway, tools, speaker).post(new BrainEvent.PlayerSaid(OWNER, "Steve", "hi", BrainEvent.Source.CHAT));

        assertFalse(speaker.streamed.isEmpty(), "a streaming turn sends streamed speech");
        assertEquals(1, speaker.streamed.stream().filter(s -> s.last()).count(), "exactly one last sentence");
        assertEquals(
                "Hello there. Second.",
                speaker.streamed.stream().map(RecordingSpeaker.Streamed::text).reduce("", String::concat));
        assertEquals(1, speaker.said.size(), "the full reply reaches chat once");
        assertTrue(speaker.said.getFirst().contains("Hello there. Second."));
    }

    @Test
    void everyStreamedRequestCarriesAFreshValidIdentity() {
        StreamingGateway gateway = new StreamingGateway();
        gateway.steps.add(new Step(
                List.of(new StreamDelta.Text("One."), new StreamDelta.Done()),
                new LlmResponse("One.", List.of(), StopReason.END, null)));
        gateway.steps.add(new Step(
                List.of(new StreamDelta.Text("Two."), new StreamDelta.Done()),
                new LlmResponse("Two.", List.of(), StopReason.END, null)));
        FakeToolExecutor tools = new FakeToolExecutor();
        RecordingSpeaker speaker = new RecordingSpeaker();
        CompanionBrain brain = brain(gateway, tools, speaker);

        brain.post(new BrainEvent.PlayerSaid(OWNER, "Steve", "one", BrainEvent.Source.CHAT));
        brain.post(new BrainEvent.PlayerSaid(OWNER, "Steve", "two", BrainEvent.Source.CHAT));

        assertEquals(4, speaker.streamed.size(), speaker.streamed.toString());
        for (int i = 0; i < speaker.streamed.size(); i++) {
            RecordingSpeaker.Streamed streamed = speaker.streamed.get(i);
            assertTrue(streamed.streamId() >= 1 && streamed.sessionId() >= 1, "a positive identity: " + streamed);
            assertEquals(streamed.streamId(), streamed.sessionId(), "one identity per request: " + streamed);
            assertEquals(i % 2, streamed.sequence(), "each request restarts its sequence: " + streamed);
        }
        assertNotEquals(
                speaker.streamed.get(0).streamId(),
                speaker.streamed.get(2).streamId(),
                "a second reply must not reuse the first reply's stream: " + speaker.streamed);
    }

    @Test
    void anIncompleteEarlyStreamReportsOneNotice() {
        CompletableFuture<LlmResponse> pending = new CompletableFuture<>();
        List<Consumer<StreamDelta>> late = new ArrayList<>();
        StreamingLlmGateway gateway = new StreamingLlmGateway() {
            @Override
            public CompletableFuture<LlmResponse> complete(
                    String system, List<Message> messages, List<ToolSpec> tools) {
                throw new UnsupportedOperationException("streaming only");
            }

            @Override
            public CompletableFuture<LlmResponse> completeStreaming(
                    String system, List<Message> messages, List<ToolSpec> tools, Consumer<StreamDelta> onDelta) {
                late.add(onDelta);
                onDelta.accept(new StreamDelta.Text("Hello."));
                return pending;
            }
        };
        RecordingSpeaker speaker = new RecordingSpeaker();
        CompanionBrain brain = brain(gateway, new FakeToolExecutor(), speaker);
        brain.post(new BrainEvent.PlayerSaid(OWNER, "Steve", "hi", BrainEvent.Source.CHAT));
        late.getFirst().accept(new StreamDelta.Text("```unfinished"));
        pending.complete(new LlmResponse("Hello. ```unfinished", List.of(), StopReason.END, null));

        assertEquals(
                1,
                speaker.errors.stream()
                        .filter(m -> m.key().equals("friendai.voice.stream_incomplete"))
                        .count(),
                "errors=" + speaker.errors + " streamed=" + speaker.streamed);
    }

    @Test
    void unsafeEarlyTextIsNotStreamed() {
        StreamingGateway gateway = new StreamingGateway();
        gateway.steps.add(new Step(
                List.of(new StreamDelta.Text("The hive is at 10 20 30."), new StreamDelta.Done()),
                new LlmResponse("The hive is at 10 20 30.", List.of(), StopReason.END, null)));
        RecordingSpeaker speaker = new RecordingSpeaker();

        brain(gateway, new FakeToolExecutor(), speaker)
                .post(new BrainEvent.PlayerSaid(OWNER, "Steve", "hi", BrainEvent.Source.CHAT));

        assertTrue(speaker.streamed.isEmpty(), speaker.streamed.toString());
        assertTrue(speaker.said.stream().noneMatch(line -> line.contains("10 20 30")), speaker.said.toString());
    }

    @Test
    void cancelDropsLaterEarlySentences() {
        CompletableFuture<LlmResponse> pending = new CompletableFuture<>();
        List<Consumer<StreamDelta>> late = new ArrayList<>();
        StreamingLlmGateway gateway = new StreamingLlmGateway() {
            @Override
            public CompletableFuture<LlmResponse> complete(
                    String system, List<Message> messages, List<ToolSpec> tools) {
                throw new UnsupportedOperationException("streaming only");
            }

            @Override
            public CompletableFuture<LlmResponse> completeStreaming(
                    String system, List<Message> messages, List<ToolSpec> tools, Consumer<StreamDelta> onDelta) {
                late.add(onDelta);
                onDelta.accept(new StreamDelta.Text("First. Second."));
                return pending;
            }
        };
        RecordingSpeaker speaker = new RecordingSpeaker();
        CompanionBrain brain = brain(gateway, new FakeToolExecutor(), speaker);
        brain.post(new BrainEvent.PlayerSaid(OWNER, "Steve", "hi", BrainEvent.Source.CHAT));
        assertEquals(1, speaker.streamed.size());
        brain.cancel();
        late.getFirst().accept(new StreamDelta.Text("Third."));
        pending.complete(new LlmResponse("First. Second. Third.", List.of(), StopReason.END, null));

        assertEquals(
                "First. Second.",
                speaker.streamed.stream().map(RecordingSpeaker.Streamed::text).reduce("", String::concat));
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

    @Test
    void aThrowingSpeakerCannotLeaveTheBrainThinking() {
        ScriptedStreamingGateway gateway = new ScriptedStreamingGateway();
        CompletableFuture<LlmResponse> first = new CompletableFuture<>();
        gateway.answers.add(first);
        gateway.answers.add(CompletableFuture.completedFuture(reply("Fine.")));
        FlakySpeaker speaker = new FlakySpeaker();
        speaker.failOnceAt.add(1L);
        CompanionBrain brain = brain(gateway, new FakeToolExecutor(), speaker);

        brain.post(said("hi"));
        gateway.deltas.getFirst().accept(new StreamDelta.Text("Hello."));
        gateway.deltas.getFirst().accept(new StreamDelta.Text(" There."));
        first.complete(reply("Hello. There."));

        assertFalse(brain.isThinking(), "a failed frame must not leave the brain thinking");
        assertFalse(brain.hasPendingWork());
        assertEquals(List.of("Hello. There."), speaker.sent.said, "the full reply still reaches chat");
        assertTrue(speaker.sent.streamed.getLast().last(), "the stream is closed: " + speaker.sent.streamed);

        brain.post(said("again"));
        assertEquals(2, gateway.deltas.size(), "the next message is answered");
        assertFalse(brain.isThinking());
    }

    @Test
    void aTransientFrameFailureClosesWithoutASequenceGap() {
        ScriptedStreamingGateway gateway = new ScriptedStreamingGateway();
        CompletableFuture<LlmResponse> first = new CompletableFuture<>();
        gateway.answers.add(first);
        FlakySpeaker speaker = new FlakySpeaker();
        speaker.failOnceAt.add(1L);
        CompanionBrain brain = brain(gateway, new FakeToolExecutor(), speaker);

        brain.post(said("hi"));
        gateway.deltas.getFirst().accept(new StreamDelta.Text("Hello."));
        gateway.deltas.getFirst().accept(new StreamDelta.Text(" There."));
        first.complete(reply("Hello. There."));

        List<RecordingSpeaker.Streamed> frames = speaker.sent.streamed;
        for (int i = 0; i < frames.size(); i++) {
            assertEquals(i, frames.get(i).sequence(), "contiguous sequences: " + frames);
        }
        assertEquals(1, frames.stream().filter(RecordingSpeaker.Streamed::last).count(), frames.toString());
        assertTrue(
                frames.stream().noneMatch(frame -> frame.text().contains("There")),
                "the failed sentence is not retried: " + frames);
        assertTrue(speaker.rejected.contains("1: There."), speaker.rejected.toString());

        SpeechStreamBuffer buffer = new SpeechStreamBuffer();
        buffer.beginSession(1);
        for (RecordingSpeaker.Streamed frame : frames) {
            buffer.offer(new SpeechStreamBuffer.Sentence(
                    frame.streamId(), frame.sessionId(), frame.sequence(), frame.text(), frame.last()));
        }
        assertFalse(buffer.incomplete(frames.getFirst().streamId()), "the client stream is completed");
    }

    @Test
    void aPersistentSpeechFailureDoesNotPreventTheNextTurn() {
        ScriptedStreamingGateway gateway = new ScriptedStreamingGateway();
        gateway.answers.add(CompletableFuture.completedFuture(reply("Hello.")));
        gateway.answers.add(CompletableFuture.completedFuture(reply("Again.")));
        FlakySpeaker speaker = new FlakySpeaker();
        speaker.failAllStreams = true;
        CompanionBrain brain = brain(gateway, new FakeToolExecutor(), speaker);

        brain.post(said("hi"));

        assertEquals(2, speaker.streamAttempts, "one text attempt and one close attempt: " + speaker.rejected);
        assertFalse(brain.isThinking());
        assertFalse(brain.hasPendingWork());

        brain.post(said("again"));
        assertEquals(2, gateway.deltas.size(), "the next message is still processed");
        assertFalse(brain.isThinking());
    }

    @Test
    void staleDeltasCannotSpeakInTheNextRequest() {
        ScriptedStreamingGateway gateway = new ScriptedStreamingGateway();
        CompletableFuture<LlmResponse> first = new CompletableFuture<>();
        CompletableFuture<LlmResponse> second = new CompletableFuture<>();
        gateway.answers.add(first);
        gateway.answers.add(second);
        RecordingSpeaker speaker = new RecordingSpeaker();
        QueueingScheduler scheduler = new QueueingScheduler();
        CompanionBrain brain = brain(gateway, new FakeToolExecutor(), speaker, scheduler);

        brain.post(said("one"));
        gateway.deltas.getFirst().accept(new StreamDelta.Text("One."));
        scheduler.drain();
        first.complete(reply("One."));
        scheduler.drain();
        assertFalse(brain.isThinking());
        int completedFrames = speaker.streamed.size();

        gateway.deltas.getFirst().accept(new StreamDelta.Text(" Later."));
        scheduler.drain();
        assertEquals(completedFrames, speaker.streamed.size(), "a completed request accepts no queued delta");

        brain.post(said("two"));
        gateway.deltas.getFirst().accept(new StreamDelta.Text(" Stale."));
        scheduler.drain();
        assertEquals(completedFrames, speaker.streamed.size(), "an old delta cannot speak in the next request");

        gateway.deltas.get(1).accept(new StreamDelta.Text("Two."));
        scheduler.drain();
        second.complete(reply("Two."));
        scheduler.drain();

        assertEquals(
                "One.Two.",
                speaker.streamed.stream().map(RecordingSpeaker.Streamed::text).reduce("", String::concat));
    }

    @Test
    void aThrowingSayStillEndsTheTurnAndProcessesQueuedWork() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(FakeGateway.text("one"));
        gateway.enqueue(FakeGateway.text("two"));
        FlakySpeaker speaker = new FlakySpeaker();
        speaker.failSay = true;
        CompanionBrain brain = brain(gateway, new FakeToolExecutor(), speaker);

        brain.post(said("first"));

        assertFalse(brain.isThinking(), "a broken chat line must not skip the turn cleanup");
        assertFalse(brain.hasPendingWork());
        brain.post(said("second"));
        assertEquals(2, gateway.requests.size(), "the next turn still runs");
        assertFalse(brain.isThinking());
    }

    @Test
    void aThrowingThinkingCannotStallTheTurnLoop() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(FakeGateway.text("one"));
        gateway.enqueue(FakeGateway.text("two"));
        FlakySpeaker speaker = new FlakySpeaker();
        speaker.failThinking = true;
        CompanionBrain brain = brain(gateway, new FakeToolExecutor(), speaker);

        brain.post(said("first"));

        assertEquals(1, gateway.requests.size(), "the turn runs even when the thinking indicator fails");
        assertFalse(brain.isThinking());
        brain.post(said("second"));
        assertEquals(2, gateway.requests.size(), "the next turn still runs");
    }

    @Test
    void aThrowingErrorStillFinishesTheTurnCleanup() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueueFailure(new LlmException(LlmErrorKind.AUTH, "bad key"));
        gateway.enqueue(FakeGateway.text("back"));
        FlakySpeaker speaker = new FlakySpeaker();
        speaker.failError = true;
        CompanionBrain brain = brain(gateway, new FakeToolExecutor(), speaker);

        brain.post(said("hi"));

        assertFalse(brain.isThinking(), "a failed error line must not skip the turn cleanup");
        brain.post(said("again"));
        assertEquals(List.of("back"), speaker.sent.said);
    }
}
