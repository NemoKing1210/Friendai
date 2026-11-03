package dev.friendai.client.voice;

import dev.friendai.FriendaiMod;
import dev.friendai.features.speech.AudioLevel;
import dev.friendai.features.speech.PcmConverter;
import dev.friendai.features.speech.VoiceErrors;
import dev.friendai.features.speech.wake.WakeCaptureSession;
import dev.friendai.features.speech.wake.WakeModelCatalog;
import dev.friendai.features.speech.wake.WakeWordPolicy;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.presentation.companion.ClientSessionId;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Hands-free listening: while the owner has the wake word enabled, this keeps the microphone open in monitoring mode,
 * asks the local detector for a hit on every captured chunk, and once the phrase is heard records the utterance that
 * follows and sends it through the same transcript path push-to-talk uses.
 *
 * <p>Every rule that decides whether audio may be captured lives here and nowhere else: the wake setting off, no
 * client session, an open screen, no player, or the companion speaking all stop the microphone and clear the
 * pending utterance, so a disconnect or a menu can never leave a stale phrase to be transcribed later. A hit in the
 * wrong state, a hit for another phrase the bundle carries, and a quiet recording all send no turn at all.
 *
 * <p>The handler owns no clock and no Minecraft type: the game facts arrive through {@link Host}, the microphone
 * through {@link Capture}, and the detector through {@link Detector}. That is also what lets the unit tests drive the
 * whole path with a fake detector and a fake microphone.
 */
public final class WakeWordHandler {
    /** The shortest utterance worth transcribing: 0.3 s at 16 kHz, 16-bit mono, like push-to-talk. */
    static final int MIN_UTTERANCE_BYTES = 9_600;

    /** Pre-roll the session keeps so the first syllable after the phrase is not clipped; never uploaded on its own. */
    static final int SESSION_PRE_ROLL_BYTES = 16_000;

    /** Loudness above which the utterance is alive; below it the silence counter runs. */
    static final float SPEECH_LEVEL = 0.02f;

    /** How long a microphone that refused to open is left alone before the next attempt. */
    private static final long MIC_RETRY_MILLIS = 10_000;

    /** A local detector. It must never upload audio; production uses the native keyword spotter. */
    public interface Detector extends AutoCloseable {
        /** Starts loading in the background; repeated calls are ignored. */
        default void prepare() {}

        /** Feeds one chunk of 16 kHz mono audio and reports a hit for the configured phrase. */
        boolean hit(float[] samples16k);

        /** Why the detector cannot run, or null while it can (or is still loading). */
        default UserMessage problem() {
            return null;
        }

        @Override
        default void close() {}
    }

    /** Receives ordered 16 kHz 16-bit mono PCM on the capture thread; the array is reused after the call returns. */
    @FunctionalInterface
    public interface ChunkSink {
        void onChunk(byte[] pcm16le, int length);
    }

    /** The microphone, in the two modes the wake word needs. */
    public interface Capture extends AutoCloseable {
        /** Opens the line and delivers chunks without accumulating a clip; throws when the device is unavailable. */
        void startMonitoring(int preRollSeconds, ChunkSink chunks) throws Exception;

        /** Closes the line. Safe to call when nothing is open. */
        void stop();

        @Override
        default void close() {
            stop();
        }
    }

    /** The game-side facts the handler asks for, so the pipeline has no Minecraft dependency of its own. */
    public interface Host {
        /** True when listening is currently allowed: session, player, no screen, both switches on, companion silent. */
        boolean canListen();

        /** True while this session and capture generation are still the live one. */
        boolean isCurrent(ClientSessionId session, int generation);

        /** Starts a new capture generation, invalidating every in-flight transcript. */
        int nextGeneration();

        /** Runs a completion on the client thread. */
        void execute(Runnable task);
    }

    private final Capture capture;
    private final Host host;
    private final Supplier<ClientConfig> config;
    private final Supplier<SpeechToText> stt;
    private final Supplier<ClientSessionId> session;
    private final BiConsumer<ClientSessionId, String> onTranscript;
    private final Supplier<Detector> detectors;
    private final WakeWordPolicy.Config policyTemplate;

    private WakeCaptureSession wake;
    private Detector detector;
    private String detectorPhrase;
    private ClientSessionId listeningSession;
    private boolean reportedProblem;
    private boolean pushToTalk;
    private boolean closed;
    private long micRetryAt;

    /** Production constructor: the one microphone the client has, and the real policy timings. */
    public WakeWordHandler(
            MicCapture mic,
            Supplier<String> deviceName,
            Host host,
            Supplier<ClientConfig> config,
            Supplier<SpeechToText> stt,
            Supplier<ClientSessionId> session,
            BiConsumer<ClientSessionId, String> onTranscript,
            Supplier<Detector> detectors) {
        this(
                microphone(mic, deviceName),
                host,
                config,
                stt,
                session,
                onTranscript,
                detectors,
                new WakeWordPolicy.Config(
                        true,
                        WakeWordPolicy.Config.DEFAULTS.cooldownTicks(),
                        WakeWordPolicy.Config.DEFAULTS.maxUtteranceTicks(),
                        WakeWordPolicy.Config.DEFAULTS.silenceTicks()));
    }

    /** Test seam: a scripted microphone and policy so a whole utterance can be driven without a device. */
    WakeWordHandler(
            Capture capture,
            Host host,
            Supplier<ClientConfig> config,
            Supplier<SpeechToText> stt,
            Supplier<ClientSessionId> session,
            BiConsumer<ClientSessionId, String> onTranscript,
            Supplier<Detector> detectors,
            WakeWordPolicy.Config policy) {
        this.capture = capture;
        this.host = host;
        this.config = config;
        this.stt = stt;
        this.session = session;
        this.onTranscript = onTranscript;
        this.detectors = detectors;
        this.policyTemplate = policy;
    }

    /** Registers the production microphone behind the wake-word seam. */
    public static Capture microphone(MicCapture mic, Supplier<String> deviceName) {
        return new Capture() {
            @Override
            public void startMonitoring(int preRollSeconds, ChunkSink chunks) throws Exception {
                mic.startMonitoring(deviceName.get(), preRollSeconds, chunks::onChunk);
            }

            @Override
            public void stop() {
                mic.stop();
            }
        };
    }

    /** Called once per client tick by the registration in {@code FriendaiClient}. */
    public void tick() {
        if (closed) {
            return;
        }
        ClientConfig.VoiceSection voice = config.get().voice;
        if (!voice.wake.enabled) {
            // The switch is the arm: off means the microphone closes and the native detector is released.
            if (detector != null || listening()) {
                disarm();
            }
            return;
        }
        if (pushToTalk || !host.canListen()) {
            suspend();
            return;
        }
        if (!listening()) {
            if (!ensureListening(voice)) {
                return;
            }
        }
        Detector running;
        synchronized (this) {
            running = detector;
        }
        if (running != null && running.problem() != null) {
            // The native detector failed after the line opened: report once and close the microphone.
            reportOnce(running.problem());
            disarm();
            return;
        }
        WakeCaptureSession active;
        synchronized (this) {
            active = wake;
        }
        if (active == null) {
            return;
        }
        Optional<byte[]> finished;
        synchronized (active) {
            finished = active.tick();
        }
        finished.ifPresent(this::onUtterance);
    }

    /** Push-to-talk owns the microphone while it is held; the wake word goes quiet and re-arms on release. */
    public void setPushToTalkCapturing(boolean capturing) {
        pushToTalk = capturing;
        if (capturing) {
            suspend();
        }
    }

    /** The owner turned the switch off, the phrase or device changed, or the connection ended. */
    public void disarm() {
        suspend();
        Detector closing;
        synchronized (this) {
            closing = detector;
            detector = null;
        }
        if (closing != null) {
            closing.close();
        }
    }

    /** Drops the microphone and everything captured so far; a running transcript becomes stale. */
    public void onDisconnect(ClientSessionId disconnected) {
        boolean ours;
        synchronized (this) {
            ours = listeningSession == null || listeningSession.equals(disconnected);
        }
        if (!ours) {
            return;
        }
        disarm();
        // A transcript from the closed connection can no longer be delivered, so the indicator must not keep it.
        VoiceState.set(VoiceState.Kind.IDLE, null);
    }

    public void close() {
        closed = true;
        disarm();
    }

    private boolean listening() {
        synchronized (this) {
            return wake != null;
        }
    }

    /**
     * Opens the microphone in monitoring mode and arms the policy. A detector that is still loading is fine: the
     * chunks are dropped until it is ready, so the indicator can appear immediately after the owner asks for it.
     */
    private boolean ensureListening(ClientConfig.VoiceSection voice) {
        long now = System.currentTimeMillis();
        if (now < micRetryAt) {
            return false;
        }
        ClientSessionId sessionId = session.get();
        if (sessionId == null) {
            return false;
        }
        String phrase = WakeModelCatalog.normalize(voice.wake.phrase);
        Detector stale = null;
        Detector active;
        synchronized (this) {
            if (detector != null && !phrase.equals(detectorPhrase)) {
                // A new phrase needs a detector opened with its own keyword; the old one is released off this tick.
                stale = detector;
                detector = null;
            }
            if (detector == null) {
                detector = detectors.get();
                detectorPhrase = phrase;
            }
            active = detector;
        }
        if (stale != null) {
            stale.close();
        }
        if (active == null) {
            return false;
        }
        active.prepare();
        UserMessage problem = active.problem();
        if (problem != null) {
            reportOnce(problem);
            return false;
        }
        try {
            capture.startMonitoring(voice.wake.preRollSec, this::onChunk);
        } catch (Exception unavailable) {
            FriendaiMod.LOGGER.warn("Could not open the microphone for the wake word", unavailable);
            micRetryAt = now + MIC_RETRY_MILLIS;
            reportOnce(UserMessage.of("friendai.voice.error.no_mic"));
            return false;
        }
        synchronized (this) {
            WakeCaptureSession created =
                    new WakeCaptureSession(armedPolicy(policyTemplate), this::detect, SESSION_PRE_ROLL_BYTES);
            created.arm();
            wake = created;
            listeningSession = sessionId;
            reportedProblem = false;
        }
        VoiceState.set(VoiceState.Kind.LISTENING, null);
        return true;
    }

    /** One captured chunk, on the capture thread: pre-roll until a hit, the utterance afterwards. */
    private void onChunk(byte[] pcm, int length) {
        WakeCaptureSession active;
        synchronized (this) {
            active = wake;
        }
        if (active == null || pcm == null || length <= 0) {
            return;
        }
        boolean loud = AudioLevel.of(pcm, length) >= SPEECH_LEVEL;
        Optional<byte[]> finished;
        synchronized (active) {
            finished = active.feed(pcm, length, loud);
        }
        finished.ifPresent(this::onUtterance);
    }

    /** The detector seam the session calls; the bytes are converted on the capture thread it already runs on. */
    private boolean detect(byte[] pcm, int length) {
        Detector active;
        synchronized (this) {
            active = detector;
        }
        if (active == null) {
            return false;
        }
        int valid = Math.max(0, Math.min(length, pcm.length));
        if (valid <= 0) {
            return false;
        }
        return active.hit(PcmConverter.toFloat(Arrays.copyOf(pcm, valid)));
    }

    /** A finished utterance: transcribe it and hand the text on, unless a newer capture has taken over. */
    private void onUtterance(byte[] pcm) {
        ClientSessionId sessionId;
        synchronized (this) {
            sessionId = listeningSession;
        }
        if (sessionId == null || pcm == null || pcm.length < MIN_UTTERANCE_BYTES) {
            return;
        }
        SpeechToText recognizer;
        try {
            recognizer = stt.get();
        } catch (RuntimeException failure) {
            FriendaiMod.LOGGER.warn("Could not initialize speech recognition for the wake word", failure);
            reportOnce(UserMessage.of("friendai.voice.error.no_model"));
            return;
        }
        if (!recognizer.isReady()) {
            reportOnce(UserMessage.of("friendai.voice.error.no_model"));
            return;
        }
        int generation = host.nextGeneration();
        VoiceState.set(VoiceState.Kind.TRANSCRIBING, null);
        CompletableFuture<String> transcript = transcribe(recognizer, pcm, config.get().voice.stt.language);
        transcript.whenComplete((text, error) -> host.execute(() -> {
            if (!host.isCurrent(sessionId, generation)) {
                // A disconnect, a newer press or a disabled switch already owns the HUD; no turn is sent.
                return;
            }
            if (error != null) {
                FriendaiMod.LOGGER.warn("Wake word speech recognition failed", error);
                VoiceState.set(VoiceState.Kind.ERROR, VoiceErrors.userMessage(error));
                return;
            }
            restoreIndicator();
            if (text != null && !text.isBlank()) {
                onTranscript.accept(sessionId, text.strip());
            }
        }));
    }

    /** Streaming recognizers need their session for the whole clip; a clip-only one is called directly. */
    private CompletableFuture<String> transcribe(SpeechToText recognizer, byte[] pcm, String language) {
        float[] samples = PcmConverter.toFloat(pcm);
        if (!(recognizer instanceof StreamingSpeechToText streaming) || !streaming.supportsStreaming()) {
            return recognizer.transcribe(samples, language);
        }
        StreamingSpeechToText.StreamingSession utterance;
        try {
            utterance = streaming.startStreaming(language, null);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        try {
            utterance.feed(samples);
        } catch (RuntimeException overflow) {
            utterance.cancel();
            return CompletableFuture.failedFuture(overflow);
        }
        return utterance.finish();
    }

    /** Puts the HUD back to listening when the wake word is still armed, and to idle when it is not. */
    private void restoreIndicator() {
        boolean listening = listening();
        VoiceState.set(listening ? VoiceState.Kind.LISTENING : VoiceState.Kind.IDLE, null);
    }

    /** Closes the microphone and clears the pending utterance, dropping anything captured so far. */
    private void suspend() {
        WakeCaptureSession closing;
        boolean wasListening;
        synchronized (this) {
            closing = wake;
            wasListening = closing != null;
            wake = null;
            listeningSession = null;
        }
        if (closing != null) {
            synchronized (closing) {
                closing.disarm();
            }
        }
        capture.stop();
        if (wasListening && VoiceState.current().kind() == VoiceState.Kind.LISTENING) {
            VoiceState.set(VoiceState.Kind.IDLE, null);
        }
    }

    private void reportOnce(UserMessage message) {
        synchronized (this) {
            if (reportedProblem) {
                return;
            }
            reportedProblem = true;
        }
        VoiceState.set(VoiceState.Kind.ERROR, message);
    }

    /** The policy the handler runs is always enabled: the settings switch, read every tick, is the real gate. */
    private static WakeWordPolicy armedPolicy(WakeWordPolicy.Config template) {
        return new WakeWordPolicy(new WakeWordPolicy.Config(
                true, template.cooldownTicks(), template.maxUtteranceTicks(), template.silenceTicks()));
    }
}
