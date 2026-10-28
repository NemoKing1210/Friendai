package dev.friendai.client.voice;

import dev.friendai.FriendaiMod;
import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local speech recognition with whisper.cpp. If the native library cannot load, {@link #isReady()} is false. */
public final class WhisperLocalStt implements SpeechToText, AutoCloseable {
    private static boolean libraryLoaded;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "friendai-whisper");
        t.setDaemon(true);
        return t;
    });
    private WhisperJNI whisper;
    private WhisperContext context;

    public WhisperLocalStt(Path model) {
        try {
            loadLibrary();
            whisper = new WhisperJNI();
            context = whisper.init(model);
        } catch (Exception | UnsatisfiedLinkError e) {
            FriendaiMod.LOGGER.warn("Could not start local speech recognition", e);
            context = null;
        }
    }

    private static synchronized void loadLibrary() throws java.io.IOException {
        if (!libraryLoaded) {
            WhisperJNI.loadLibrary();
            libraryLoaded = true;
        }
    }

    @Override
    public boolean isReady() {
        return context != null;
    }

    @Override
    public CompletableFuture<String> transcribe(float[] samples16k, String language) {
        return CompletableFuture.supplyAsync(
                () -> {
                    WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
                    params.printProgress = false;
                    params.printRealtime = false;
                    params.printTimestamps = false;
                    params.nThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
                    params.language = language == null || language.isBlank() ? "auto" : language;
                    int result = whisper.full(context, params, samples16k, samples16k.length);
                    if (result != 0) {
                        throw new IllegalStateException("whisper returned " + result);
                    }
                    StringBuilder text = new StringBuilder();
                    int segments = whisper.fullNSegments(context);
                    for (int i = 0; i < segments; i++) {
                        text.append(whisper.fullGetSegmentText(context, i));
                    }
                    return text.toString().strip();
                },
                executor);
    }

    @Override
    public void close() {
        executor.execute(() -> {
            if (context != null) {
                whisper.free(context);
                context = null;
            }
        });
        executor.shutdown();
    }
}
