package dev.friendai.client.voice;

import dev.friendai.client.FriendaiClient;
import dev.friendai.client.audio.NativeAudioGuard;
import dev.friendai.client.audio.WhisperModel;
import dev.friendai.client.audio.WhisperModelManager;
import dev.friendai.features.speech.ResolvedStt;
import dev.friendai.features.speech.VoiceReadiness;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.infrastructure.config.VoiceConfig;
import dev.friendai.infrastructure.speech.SttClient;
import dev.friendai.infrastructure.speech.VoiceHttp;
import dev.friendai.presentation.companion.ClientSessionId;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** The client's speech recognizer and model downloads. */
public final class VoiceRuntime {
    /** Whether recognition can run right now, and what to tell the player when it cannot. */
    public record SttStatus(boolean ready, UserMessage problem) {}

    private static final SpeechToText NOT_READY = new SpeechToText() {
        @Override
        public boolean isReady() {
            return false;
        }

        @Override
        public CompletableFuture<String> transcribe(float[] samples16k, String language) {
            return CompletableFuture.failedFuture(new IllegalStateException("speech recognition is not configured"));
        }
    };

    private static final HttpClient HTTP =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();

    /** Recognition uploads up to 30 s of audio, so it gets a longer budget than synthesis. */
    private static final SttClient STT = SttClient.withBuiltins(new VoiceHttp(HTTP, Duration.ofSeconds(60)));

    private static final WhisperModelManager MANAGER = new WhisperModelManager(
            FabricLoader.getInstance().getGameDir().resolve("friendai").resolve("models"),
            HTTP,
            WhisperModel.DOWNLOAD_BASE_URL);

    private static final MicCapture MIC = new MicCapture();

    private static WhisperLocalStt current;
    private static String currentModel;
    private static CloudStt currentCloud;
    private static String currentCloudKey;
    private static int captureGeneration;
    private static ClientSessionId sessionId;

    private VoiceRuntime() {}

    /** The one microphone the client records from; the HUD reads its level while recording. */
    public static MicCapture mic() {
        return MIC;
    }

    public static WhisperModelManager manager() {
        return MANAGER;
    }

    /** The recognizer for the configured provider, or one that reports "not ready" when it is unavailable. */
    public static synchronized SpeechToText stt() {
        ResolvedStt resolved = resolveStt();
        if (!resolved.local()) {
            return cloudStt(resolved);
        }
        WhisperModel wanted = WhisperModel.byId(FriendaiClient.clientConfig().voice.stt.local.model);
        if (current != null && wanted.id().equals(currentModel)) {
            return current;
        }
        // Rebuild only the local engine; an external invalidate() bumps the capture generation,
        // but loading a model for the current press must not invalidate that press.
        if (current != null) {
            current.close();
            current = null;
            currentModel = null;
        }
        if (!MANAGER.isInstalled(wanted.spec())) {
            return NOT_READY;
        }
        NativeAudioGuard.requireClientEnvironment();
        WhisperLocalStt stt = new WhisperLocalStt(MANAGER.path(wanted.spec()));
        if (!stt.isReady()) {
            stt.close();
            return NOT_READY;
        }
        current = stt;
        currentModel = wanted.id();
        return stt;
    }

    /** Whether recognition can run right now, and what to tell the player when it cannot. */
    public static synchronized SttStatus sttStatus() {
        ResolvedStt resolved = resolveStt();
        boolean installed = resolved.local()
                && MANAGER.isInstalled(WhisperModel.byId(FriendaiClient.clientConfig().voice.stt.local.model)
                        .spec());
        UserMessage problem = VoiceReadiness.stt(resolved, installed);
        if (problem != null) {
            return new SttStatus(false, problem);
        }
        // This is used while building the settings screen: do not initialize a native engine merely
        // to display configured availability. The explicit recording action performs the load check.
        return new SttStatus(true, null);
    }

    public static synchronized void onConnect(ClientSessionId connectedSession) {
        sessionId = connectedSession;
        captureGeneration++;
    }

    public static void onDisconnect(ClientSessionId disconnectedSession) {
        synchronized (VoiceRuntime.class) {
            if (!disconnectedSession.equals(sessionId)) return;
            sessionId = null;
            captureGeneration++;
            MIC.stop();
            closeRecognizer();
            currentCloud = null;
            currentCloudKey = null;
        }
        VoiceState.set(VoiceState.Kind.IDLE, null);
    }

    public static synchronized boolean isCurrentSession(ClientSessionId expected) {
        return expected != null && expected.equals(sessionId);
    }

    public static synchronized boolean isCurrentOperation(ClientSessionId expectedSession, int expectedGeneration) {
        return java.util.Objects.equals(expectedSession, sessionId) && expectedGeneration == captureGeneration;
    }

    /** Releases client-process audio resources; model downloads remain owned by their global managers. */
    public static void close() {
        synchronized (VoiceRuntime.class) {
            sessionId = null;
            captureGeneration++;
            MIC.close();
            closeRecognizer();
            currentCloud = null;
            currentCloudKey = null;
        }
        VoiceState.set(VoiceState.Kind.IDLE, null);
    }

    /**
     * A token for the next recording. Starting a recording or invalidating the runtime bumps it, so
     * a transcript that arrives late (cloud calls may take a minute) can be recognised as stale.
     */
    public static synchronized int nextCaptureGeneration() {
        return ++captureGeneration;
    }

    public static synchronized int captureGeneration() {
        return captureGeneration;
    }

    /** Drops the loaded recognizer so the next use picks up a changed provider, key or model. */
    public static synchronized void invalidate() {
        captureGeneration++;
        MIC.stop();
        closeRecognizer();
        currentCloud = null;
        currentCloudKey = null;
        VoiceState.set(VoiceState.Kind.IDLE, null);
    }

    private static void closeRecognizer() {
        if (current != null) {
            current.close();
            current = null;
            currentModel = null;
        }
    }

    private static SpeechToText cloudStt(ResolvedStt resolved) {
        String key = cloudKey(resolved);
        if (currentCloud != null && key.equals(currentCloudKey)) {
            return currentCloud;
        }
        // No key gate here: VoiceReadiness already allows a keyless explicit base URL, and
        // VoiceAuth simply omits the header for a blank key.
        currentCloud = new CloudStt(resolved, STT);
        currentCloudKey = key;
        return currentCloud;
    }

    /**
     * Identifies the recognizer a resolved configuration needs, so a changed key, model or language
     * is not served by a cached one. The key travels hashed, as in {@link VoiceConfig#ttsCacheKey}:
     * this string is never shown to a player, but it must not carry a secret either.
     */
    private static String cloudKey(ResolvedStt resolved) {
        return resolved.providerId() + "|" + resolved.baseUrl() + "|" + resolved.model() + "|"
                + (resolved.hasKey() ? Integer.toHexString(resolved.apiKey().hashCode()) : "") + "|"
                + resolved.language() + "|" + resolved.folderId();
    }

    private static ResolvedStt resolveStt() {
        return VoiceConfig.resolveStt(
                FriendaiClient.clientConfig().voice.stt,
                Minecraft.getInstance().getLanguageManager().getSelected(),
                System::getenv);
    }
}
