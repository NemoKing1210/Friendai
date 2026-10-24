package dev.friendai.client.voice;

import dev.friendai.client.FriendaiClient;
import dev.friendai.voice.WhisperModel;
import dev.friendai.voice.WhisperModelManager;
import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;

/** The client's speech recognizer and model downloads. */
public final class VoiceRuntime {
    private static final SpeechToText NOT_READY = new SpeechToText() {
        @Override
        public boolean isReady() {
            return false;
        }

        @Override
        public CompletableFuture<String> transcribe(float[] samples16k, String language) {
            return CompletableFuture.failedFuture(new IllegalStateException("no speech model installed"));
        }
    };

    private static final WhisperModelManager MANAGER = new WhisperModelManager(
            FabricLoader.getInstance().getGameDir().resolve("friendai").resolve("models"),
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build(),
            WhisperModel.DOWNLOAD_BASE_URL);

    private static final MicCapture MIC = new MicCapture();

    private static WhisperLocalStt current;
    private static String currentModel;

    private VoiceRuntime() {}

    /** The one microphone the client records from; the HUD reads its level while recording. */
    public static MicCapture mic() {
        return MIC;
    }

    public static WhisperModelManager manager() {
        return MANAGER;
    }

    /** The recognizer for the configured model, or one that reports "not ready" if the model is not installed. */
    public static synchronized SpeechToText stt() {
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

    /** Drops the loaded recognizer so the next use picks up a changed or newly downloaded model. */
    public static synchronized void invalidate() {
        if (current != null) {
            current.close();
            current = null;
            currentModel = null;
        }
    }
}
