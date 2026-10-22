package dev.friedai.client.voice;

import dev.friedai.client.FriedaiClient;
import dev.friedai.voice.WhisperModel;
import dev.friedai.voice.WhisperModelManager;
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
            FabricLoader.getInstance().getGameDir().resolve("friedai").resolve("models"),
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build(),
            WhisperModel.DOWNLOAD_BASE_URL);

    private static WhisperLocalStt current;
    private static String currentModel;

    private VoiceRuntime() {}

    public static WhisperModelManager manager() {
        return MANAGER;
    }

    /** The recognizer for the configured model, or one that reports "not ready" if the model is not installed. */
    public static synchronized SpeechToText stt() {
        WhisperModel wanted = WhisperModel.byId(FriedaiClient.clientConfig().voice.whisperModel);
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
