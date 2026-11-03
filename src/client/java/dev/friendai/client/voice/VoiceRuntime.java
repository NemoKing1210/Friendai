package dev.friendai.client.voice;

import dev.friendai.FriendaiMod;
import dev.friendai.client.FriendaiClient;
import dev.friendai.client.audio.LocalSttBundleManager;
import dev.friendai.features.speech.ResolvedStt;
import dev.friendai.features.speech.VoiceReadiness;
import dev.friendai.features.speech.stt.LocalSttCatalog;
import dev.friendai.features.speech.stt.LocalSttCatalog.LocalSttModel;
import dev.friendai.features.speech.wake.WakeModelCatalog;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.VoiceConfig;
import dev.friendai.infrastructure.speech.SttClient;
import dev.friendai.infrastructure.speech.VoiceHttp;
import dev.friendai.presentation.companion.ClientSessionId;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    /** One catalog, one downloader: legacy Whisper and the sherpa bundles share it. */
    private static final LocalSttBundleManager MANAGER =
            new LocalSttBundleManager(FabricLoader.getInstance().getGameDir(), HTTP);

    /** The wake-word bundle installer; it downloads one small bundle and is shared by every listening session. */
    private static final WakeBundleManager WAKE_BUNDLES =
            new WakeBundleManager(FabricLoader.getInstance().getGameDir(), HTTP);

    private static final MicCapture MIC = new MicCapture();

    private static SpeechToText current;
    private static AutoCloseable currentClosable;
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

    public static LocalSttBundleManager manager() {
        return MANAGER;
    }

    public static WakeBundleManager wakeBundles() {
        return WAKE_BUNDLES;
    }

    /**
     * One wake-word detector for one listening session. The detector loads its native model on its own worker, which
     * it releases when the handler disarms, so a phrase change or a disconnect leaves nothing running.
     */
    public static WakeWordHandler.Detector wakeDetector(String phrase) {
        return new SherpaKeywordDetector(
                WakeModelCatalog.defaultModel(), WAKE_BUNDLES, phrase, worker("friendai-wake-kws"));
    }

    /** The recognizer for the configured provider, or one that reports "not ready" when it is unavailable. */
    public static synchronized SpeechToText stt() {
        return stt(FriendaiClient.clientConfig().voice.stt);
    }

    /** Explicit settings let the settings screen test its detached draft without saving it. */
    public static synchronized SpeechToText stt(ClientConfig.SttSection settings) {
        ResolvedStt resolved = resolveStt(settings);
        if (!resolved.local()) {
            return cloudStt(resolved);
        }
        LocalSttModel wanted = LocalSttCatalog.byId(settings.local.model).orElse(null);
        if (wanted == null) {
            return NOT_READY;
        }
        String key = wanted.id() + "@" + wanted.revision();
        if (current != null && key.equals(currentModel)) {
            return current;
        }
        // Rebuild only the local engine; an external invalidate() bumps the capture generation,
        // but loading a model for the current press must not invalidate that press.
        closeRecognizer();
        if (!MANAGER.hasFiles(wanted)) {
            return NOT_READY;
        }
        try {
            if (wanted.whisper()) {
                WhisperLocalStt whisper =
                        new WhisperLocalStt(MANAGER.file(wanted, wanted.files().get(0)));
                if (!whisper.isReady()) {
                    whisper.close();
                    return NOT_READY;
                }
                current = whisper;
                currentClosable = whisper;
            } else {
                SherpaStt sherpa = new SherpaStt(
                        wanted,
                        MANAGER.dir(wanted),
                        () -> MANAGER.verifyInstalled(wanted),
                        worker("friendai-stt-" + wanted.id()));
                current = sherpa;
                currentClosable = sherpa;
            }
        } catch (RuntimeException failure) {
            FriendaiMod.LOGGER.warn("Could not start local speech recognition for {}", wanted.id(), failure);
            closeRecognizer();
            return NOT_READY;
        }
        currentModel = key;
        return current;
    }

    /** Whether recognition can run right now, and what to tell the player when it cannot. */
    public static synchronized SttStatus sttStatus() {
        return sttStatus(FriendaiClient.clientConfig().voice.stt, FriendaiClient.clientConfig().voice.stt.local.model);
    }

    public static synchronized SttStatus sttStatus(ClientConfig.SttSection settings) {
        return sttStatus(settings, settings.local.model);
    }

    /**
     * Readiness for one selected catalog id, so the settings screen can show the row the player is editing while
     * the saved configuration still points at the old one.
     */
    public static synchronized SttStatus sttStatus(ClientConfig.SttSection settings, String modelId) {
        ResolvedStt resolved = resolveStt(settings);
        if (!resolved.local()) {
            return cloudStatus(resolved);
        }
        LocalSttModel model = LocalSttCatalog.byId(modelId).orElse(null);
        if (model == null) {
            return new SttStatus(false, UserMessage.of("friendai.voice.error.unknown_model"));
        }
        boolean installed = MANAGER.hasFiles(model);
        UserMessage problem = VoiceReadiness.stt(resolved, installed);
        if (problem != null) {
            return new SttStatus(false, problem);
        }
        if (!model.supportsLanguage(settings.language)) {
            return new SttStatus(false, UserMessage.of("friendai.voice.error.unsupported_language"));
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
            WAKE_BUNDLES.close();
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
        AutoCloseable closing = currentClosable;
        current = null;
        currentClosable = null;
        currentModel = null;
        if (closing != null) {
            try {
                closing.close();
            } catch (Exception | LinkageError failure) {
                FriendaiMod.LOGGER.warn("Could not close local speech recognition", failure);
            }
        }
    }

    private static SttStatus cloudStatus(ResolvedStt resolved) {
        UserMessage problem = VoiceReadiness.stt(resolved, false);
        return problem == null ? new SttStatus(true, null) : new SttStatus(false, problem);
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

    private static ExecutorService worker(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static ResolvedStt resolveStt(ClientConfig.SttSection settings) {
        return VoiceConfig.resolveStt(
                settings, Minecraft.getInstance().getLanguageManager().getSelected(), System::getenv);
    }
}
