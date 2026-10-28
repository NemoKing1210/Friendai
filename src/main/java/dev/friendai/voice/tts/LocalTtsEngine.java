package dev.friendai.voice.tts;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.voice.PcmConverter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Piper voices running locally through sherpa-onnx. Loading is lazy: constructing the engine costs nothing,
 * the native library is opened on the first request. Generate and release are serialized on one lock because
 * the native object is not safe to use from several threads.
 */
public final class LocalTtsEngine implements SpeechEngine {
    private static final int NUM_THREADS = 2;

    private final VoiceModel model;
    private final Path dir;
    private final double speed;
    private final Executor executor;
    private final Object lock = new Object();

    private OfflineTts tts;
    private boolean closed;

    public LocalTtsEngine(VoiceModel model, Path voiceDir, double speed, Executor executor) {
        this.model = model;
        this.dir = voiceDir;
        this.speed = speed;
        this.executor = executor;
    }

    @Override
    public boolean isReady() {
        synchronized (lock) {
            return !closed && filesPresent();
        }
    }

    @Override
    public CompletableFuture<TtsClip> synthesize(String text) {
        synchronized (lock) {
            if (closed) {
                return failed("friendai.voice.tts.error.failed");
            }
            if (!filesPresent()) {
                return failed("friendai.voice.tts.error.no_voice");
            }
        }
        return CompletableFuture.supplyAsync(() -> generate(text), executor);
    }

    private TtsClip generate(String text) {
        synchronized (lock) {
            if (closed) {
                throw uf("friendai.voice.tts.error.failed");
            }
            try {
                if (tts == null) {
                    tts = new OfflineTts(config());
                }
                GeneratedAudio audio = tts.generate(text, 0, (float) speed);
                return new TtsClip(PcmConverter.fromFloat(audio.getSamples()), audio.getSampleRate());
            } catch (RuntimeException | LinkageError e) {
                throw e instanceof UserFacingException
                        ? (UserFacingException) e
                        : uf("friendai.voice.tts.error.failed");
            }
        }
    }

    private OfflineTtsConfig config() {
        OfflineTtsVitsModelConfig vits = OfflineTtsVitsModelConfig.builder()
                .setModel(dir.resolve(model.modelFile()).toString())
                .setTokens(dir.resolve(model.tokensFile()).toString())
                .setDataDir(dir.resolve(model.dataDir()).toString())
                .build();
        OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setVits(vits)
                .setNumThreads(NUM_THREADS)
                .setDebug(false)
                .setProvider("cpu")
                .build();
        return OfflineTtsConfig.builder().setModel(modelConfig).build();
    }

    private boolean filesPresent() {
        return Files.isRegularFile(dir.resolve(model.modelFile()))
                && Files.isRegularFile(dir.resolve(model.tokensFile()))
                && Files.isDirectory(dir.resolve(model.dataDir()));
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            if (tts != null) {
                try {
                    tts.release();
                } catch (RuntimeException | LinkageError ignored) {
                    // Shutting down anyway; a failing native release must not break the client.
                }
                tts = null;
            }
        }
    }

    private static CompletableFuture<TtsClip> failed(String key) {
        return CompletableFuture.failedFuture(uf(key));
    }

    private static UserFacingException uf(String key) {
        return new UserFacingException(UserMessage.of(key));
    }
}
