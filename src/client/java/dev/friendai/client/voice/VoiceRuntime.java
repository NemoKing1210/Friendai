package dev.friendai.client.voice;

import dev.friendai.client.FriendaiClient;
import dev.friendai.text.UserMessage;
import dev.friendai.voice.ResolvedStt;
import dev.friendai.voice.VoiceConfig;
import dev.friendai.voice.VoiceHttp;
import dev.friendai.voice.VoiceReadiness;
import dev.friendai.voice.WhisperModel;
import dev.friendai.voice.WhisperModelManager;
import dev.friendai.voice.stt.SttClient;
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
        invalidate();
        if (!MANAGER.isInstalled(wanted.spec())) {
            return NOT_READY;
        }
        WhisperLocalStt stt = new WhisperLocalStt(MANAGER.path(wanted.spec()));
        if (!stt.isReady()) {
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
                && MANAGER.isInstalled(WhisperModel.byId(FriendaiClient.clientConfig().voice.stt.local.model).spec());
        UserMessage problem = VoiceReadiness.stt(resolved, installed);
        if (problem != null) {
            return new SttStatus(false, problem);
        }
        if (!stt().isReady()) {
            // The model is on disk but the native library could not be loaded.
            return new SttStatus(false, UserMessage.of("friendai.voice.error.no_model"));
        }
        return new SttStatus(true, null);
    }

    /** Drops the loaded recognizer so the next use picks up a changed provider, key or model. */
    public static synchronized void invalidate() {
        if (current != null) {
            current.close();
            current = null;
            currentModel = null;
        }
        currentCloud = null;
        currentCloudKey = null;
    }

    private static SpeechToText cloudStt(ResolvedStt resolved) {
        if (!resolved.hasKey()) {
            return NOT_READY;
        }
        String key = cloudKey(resolved);
        if (currentCloud != null && key.equals(currentCloudKey)) {
            return currentCloud;
        }
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
        return VoiceConfig.resolveStt(FriendaiClient.clientConfig().voice.stt,
                Minecraft.getInstance().getLanguageManager().getSelected(), System::getenv);
    }
}
