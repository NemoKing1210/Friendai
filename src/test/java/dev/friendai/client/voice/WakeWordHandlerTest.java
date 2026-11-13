package dev.friendai.client.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.speech.PcmConverter;
import dev.friendai.features.speech.wake.WakeWordPolicy;
import dev.friendai.foundation.session.ClientSessionId;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.infrastructure.config.ClientConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.LineUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The wake-word pipeline with a fake microphone and a fake detector: a hit opens one capture, a finished utterance
 * becomes exactly one transcript, and every way of leaving early — a false positive, a disarm, a disconnect, a
 * disabled setting, a microphone that refuses to open — sends no turn at all.
 */
class WakeWordHandlerTest {
    /** 0.15 s at 16 kHz: small enough to keep the test quick, long enough to clear the 0.3 s minimum. */
    private static final int CHUNK_BYTES = 4_800;

    private static final String TRANSCRIPT = "turn on the lights";

    private FakeCapture capture;
    private FakeHost host;
    private FakeDetector detector;
    private FakeStt stt;
    private ClientSessionId session;
    private ClientConfig config;
    private final List<String> transcripts = new ArrayList<>();
    private WakeWordHandler handler;

    @BeforeEach
    void setUp() {
        VoiceState.set(VoiceState.Kind.IDLE, null);
        capture = new FakeCapture();
        host = new FakeHost();
        detector = new FakeDetector();
        stt = new FakeStt();
        session = ClientSessionId.create();
        config = ClientConfig.defaults();
        config.voice.wake.enabled = true;
        transcripts.clear();
        handler = newHandler(new WakeWordPolicy.Config(true, 1, 600, 1));
    }

    private WakeWordHandler newHandler(WakeWordPolicy.Config policy) {
        return new WakeWordHandler(
                capture,
                host,
                () -> config,
                () -> stt,
                () -> session,
                (id, text) -> transcripts.add(text),
                () -> detector,
                policy);
    }

    /** A hit on the phrase, then the utterance: two loud chunks and one quiet tick that ends it. */
    private void speak() {
        detector.hitNext = true;
        capture.send(loud());
        detector.hitNext = false;
        capture.send(loud());
        capture.send(silent());
        handler.tick();
    }

    @Test
    void armingOpensTheMicrophoneAndShowsListening() {
        handler.tick();

        assertEquals(1, capture.starts);
        assertEquals(2, capture.preRollSeconds);
        assertTrue(capture.open);
        assertEquals(1, capture.starts, "a second tick must not open the line again");
        assertEquals(VoiceState.Kind.LISTENING, VoiceState.current().kind());
        assertTrue(detector.prepared > 0, "the detector is asked to load as soon as listening starts");
        assertEquals(0, transcripts.size());
    }

    @Test
    void theSettingIsOffByDefault() {
        assertFalse(ClientConfig.defaults().voice.wake.enabled);

        config = ClientConfig.defaults();
        handler.tick();

        assertEquals(0, capture.starts);
        assertEquals(VoiceState.Kind.IDLE, VoiceState.current().kind());
    }

    @Test
    void aHitRecordsTheUtteranceAndSendsOneTranscript() {
        handler.tick();

        speak();

        assertFalse(detector.hitNext);
        assertEquals(1, stt.calls);
        assertEquals(List.of(TRANSCRIPT), transcripts);
        assertEquals(1, host.generation, "one capture generation is opened for the utterance");
        assertTrue(stt.lastSamples.length > 0);
        // The HUD goes back to listening, because the wake word is still armed after the turn.
        assertEquals(VoiceState.Kind.LISTENING, VoiceState.current().kind());
    }

    @Test
    void aRecognizerWithoutAStreamingGraphIsTranscribedAsAClip() {
        OfflineStreamingStt offline = new OfflineStreamingStt();
        stt = offline;
        handler.tick();

        speak();

        assertEquals(1, offline.calls);
        assertEquals(0, offline.streams, "a clip-only model is never opened as a streaming session");
        assertEquals(List.of(TRANSCRIPT), transcripts);
    }

    @Test
    void aFalsePositiveSendsNoTurn() {
        handler.tick();
        detector.hitNext = false;

        capture.send(loud());
        capture.send(loud());
        capture.send(silent());
        for (int i = 0; i < 30; i++) {
            handler.tick();
        }

        assertEquals(0, stt.calls);
        assertEquals(List.of(), transcripts);
        assertTrue(detector.hits >= 3, "the detector still sees every chunk while listening");
        assertEquals(VoiceState.Kind.LISTENING, VoiceState.current().kind());
    }

    @Test
    void aQuietRecordingSendsNoTurn() {
        handler.tick();

        // A tiny hit chunk and one quiet chunk: the whole recording stays below the minimum utterance length.
        detector.hitNext = true;
        capture.send(pcm(1_000, 0.3f));
        detector.hitNext = false;
        capture.send(pcm(1_000, 0f));
        handler.tick();

        assertEquals(0, stt.calls);
        assertEquals(List.of(), transcripts);
    }

    @Test
    void noSessionNeverOpensTheMicrophone() {
        host.canListen = false;
        handler.tick();

        assertEquals(0, capture.starts);
        assertEquals(VoiceState.Kind.IDLE, VoiceState.current().kind());
    }

    @Test
    void aMicrophoneThatCannotOpenReportsOneErrorAndBacksOff() {
        capture.failure = new LineUnavailableException("no device");
        handler.tick();

        VoiceState.Snapshot shown = VoiceState.current();
        assertEquals(VoiceState.Kind.ERROR, shown.kind());
        assertNotNull(shown.message());
        assertEquals("friendai.voice.error.no_mic", shown.message().key());
        long since = shown.sinceMillis();

        handler.tick();
        handler.tick();

        assertEquals(1, capture.attempts, "a refused device must not be reopened on every tick");
        assertSame(shown, VoiceState.current(), "the same error is not republished");
        assertEquals(since, VoiceState.current().sinceMillis());
    }

    @Test
    void aDetectorThatCannotRunReportsItsOwnProblem() {
        detector.problem = UserMessage.of("friendai.voice.error.no_model");
        handler.tick();

        assertEquals(0, capture.starts);
        assertEquals(VoiceState.Kind.ERROR, VoiceState.current().kind());
        assertEquals(
                "friendai.voice.error.no_model", VoiceState.current().message().key());
    }

    @Test
    void disarmStopsCaptureAndSendsNothing() {
        handler.tick();

        detector.hitNext = true;
        capture.send(loud());
        assertTrue(capture.open);
        handler.disarm();

        assertFalse(capture.open, "disarming closes the microphone immediately");
        assertTrue(detector.closed);
        assertEquals(VoiceState.Kind.IDLE, VoiceState.current().kind());

        // Nothing captured after a disarm may be delivered late, and with the switch off nothing re-arms.
        config.voice.wake.enabled = false;
        capture.send(loud());
        handler.tick();
        assertEquals(1, capture.starts);
        assertEquals(0, stt.calls);
        assertEquals(List.of(), transcripts);
    }

    @Test
    void pushToTalkTakesTheMicrophoneAndHandsItBack() {
        handler.tick();
        assertTrue(capture.open);

        handler.setPushToTalkCapturing(true);
        assertFalse(capture.open);
        handler.tick();
        assertEquals(1, capture.starts, "the wake word stays quiet while the key is held");

        handler.setPushToTalkCapturing(false);
        handler.tick();
        assertEquals(2, capture.starts);
        assertTrue(capture.open);
    }

    @Test
    void aTranscriptThatArrivesAfterADisconnectIsDropped() {
        CompletableFuture<String> pending = new CompletableFuture<>();
        stt.result = pending;
        handler.tick();
        speak();
        assertEquals(1, stt.calls);

        handler.onDisconnect(session);
        host.sessionCurrent = false;
        pending.complete(TRANSCRIPT);

        assertEquals(List.of(), transcripts);
        assertEquals(VoiceState.Kind.IDLE, VoiceState.current().kind());
    }

    @Test
    void aTranscriptSupersededByANewerCaptureIsDropped() {
        CompletableFuture<String> pending = new CompletableFuture<>();
        stt.result = pending;
        handler.tick();
        speak();

        // A push-to-talk press bumps the capture generation, so the wake turn is stale.
        host.nextGeneration();
        pending.complete(TRANSCRIPT);

        assertEquals(List.of(), transcripts);
    }

    @Test
    void aRecognitionFailureBecomesOneVisibleError() {
        CompletableFuture<String> failing = new CompletableFuture<>();
        stt.result = failing;
        handler.tick();
        speak();

        failing.completeExceptionally(new IllegalStateException("recognizer failed"));

        assertEquals(VoiceState.Kind.ERROR, VoiceState.current().kind());
        assertEquals(List.of(), transcripts);
    }

    @Test
    void aSourceThatFallsSilentOnItsOwnEndsTheUtteranceOnTheNextTick() {
        // Voice chat sends nothing once the player stops talking; its microphone marks the gap with quiet audio on a
        // tick, and that must end the utterance instead of leaving it open until its 30-second cap.
        handler.tick();
        detector.hitNext = true;
        capture.send(loud());
        detector.hitNext = false;
        capture.send(loud());
        boolean[] marked = {false};
        capture.onTick = () -> {
            if (!marked[0]) {
                marked[0] = true;
                capture.send(silent());
            }
        };

        handler.tick();

        assertTrue(capture.ticks > 0, "the source is ticked while it monitors");
        assertEquals(List.of(TRANSCRIPT), transcripts);
    }

    @Test
    void closingTheHandlerStopsListening() {
        handler.tick();
        handler.close();

        assertFalse(capture.open);
        assertTrue(detector.closed);
        assertEquals(List.of(), transcripts);
        handler.tick();
        assertEquals(1, capture.starts, "a closed handler never reopens the microphone");
    }

    private static byte[] loud() {
        return pcm(CHUNK_BYTES, 0.3f);
    }

    private static byte[] pcm(int bytes, float amplitude) {
        float[] samples = new float[bytes / 2];
        Arrays.fill(samples, amplitude);
        return PcmConverter.fromFloat(samples);
    }

    private static byte[] silent() {
        return new byte[CHUNK_BYTES];
    }

    /** The microphone seam: records how it was opened and lets the test deliver chunks on demand. */
    private static final class FakeCapture implements WakeWordHandler.Capture {
        private WakeWordHandler.ChunkSink sink;
        private boolean open;
        private int starts;
        private int attempts;
        private int preRollSeconds;
        private Exception failure;

        @Override
        public void startMonitoring(int preRollSeconds, WakeWordHandler.ChunkSink chunks) throws Exception {
            attempts++;
            if (failure != null) {
                throw failure;
            }
            this.preRollSeconds = preRollSeconds;
            this.sink = chunks;
            this.open = true;
            this.starts++;
        }

        @Override
        public void stop() {
            open = false;
            sink = null;
        }

        /** What the source does on a client tick; voice chat marks the end of a transmission here. */
        private Runnable onTick = () -> {};

        private int ticks;

        @Override
        public void tick() {
            ticks++;
            onTick.run();
        }

        void send(byte[] pcm) {
            WakeWordHandler.ChunkSink target = sink;
            if (target != null) {
                target.onChunk(pcm, pcm.length);
            }
        }
    }

    /** The detector seam: one scripted verdict per chunk. */
    private static final class FakeDetector implements WakeWordHandler.Detector {
        private boolean hitNext;
        private int hits;
        private int prepared;
        private boolean closed;
        private UserMessage problem;

        @Override
        public void prepare() {
            prepared++;
        }

        @Override
        public boolean hit(float[] samples16k) {
            hits++;
            return hitNext;
        }

        @Override
        public UserMessage problem() {
            return problem;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A recognizer that carries the streaming interface but reports that its model cannot stream. */
    private static final class OfflineStreamingStt extends FakeStt implements StreamingSpeechToText {
        private int streams;

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public StreamingSpeechToText.StreamingSession startStreaming(
                String language, java.util.function.Consumer<String> partials) {
            streams++;
            throw new AssertionError("an offline model must not be opened as a stream");
        }
    }

    /** The recognizer seam: a scripted future, and the samples the handler handed over. */
    private static class FakeStt implements SpeechToText {
        private CompletableFuture<String> result = CompletableFuture.completedFuture(TRANSCRIPT);
        int calls;
        private float[] lastSamples;
        private String lastLanguage;

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public CompletableFuture<String> transcribe(float[] samples16k, String language) {
            calls++;
            lastSamples = samples16k;
            lastLanguage = language;
            return result;
        }
    }

    /** The game-side seam: whether listening is allowed and which capture generation is live. */
    private static final class FakeHost implements WakeWordHandler.Host {
        private boolean canListen = true;
        private boolean sessionCurrent = true;
        private int generation;

        @Override
        public boolean canListen() {
            return canListen;
        }

        @Override
        public boolean isCurrent(ClientSessionId session, int generation) {
            return sessionCurrent && generation == this.generation;
        }

        @Override
        public int nextGeneration() {
            return ++generation;
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }
    }
}
