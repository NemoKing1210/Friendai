package dev.friendai.client.voice;

import dev.friendai.FriendaiMod;
import dev.friendai.client.audio.NativeAudioGuard;
import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local speech recognition with whisper.cpp. If the native library cannot load, {@link #isReady()} is false. */
public final class WhisperLocalStt implements SpeechToText, AutoCloseable {
    private final Backend backend;
    private final ExecutorService executor;
    private Object context;
    private boolean closed;

    public WhisperLocalStt(Path model) {
        this(model, new JniBackend(), Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "friendai-whisper");
            thread.setDaemon(true);
            return thread;
        }));
    }

    WhisperLocalStt(Path model, Backend backend, ExecutorService executor) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.executor = Objects.requireNonNull(executor, "executor");
        try {
            context = backend.open(model);
        } catch (Exception | UnsatisfiedLinkError failure) {
            FriendaiMod.LOGGER.warn("Could not start local speech recognition", failure);
        }
    }

    @Override
    public synchronized boolean isReady() {
        return !closed && context != null;
    }

    @Override
    public synchronized CompletableFuture<String> transcribe(float[] samples16k, String language) {
        Object activeContext = context;
        if (closed || activeContext == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("speech recognition is not ready"));
        }

        Backend activeBackend = backend;
        String activeLanguage = language == null || language.isBlank() ? "auto" : language;
        return CompletableFuture.supplyAsync(
                () -> activeBackend.transcribe(activeContext, samples16k, activeLanguage), executor);
    }

    @Override
    public synchronized void close() {
        if (closed) return;

        closed = true;
        Object closingContext = context;
        context = null;
        if (closingContext != null) {
            executor.execute(() -> {
                try {
                    backend.close(closingContext);
                } catch (RuntimeException | UnsatisfiedLinkError failure) {
                    FriendaiMod.LOGGER.warn("Could not close local speech recognition", failure);
                }
            });
        }
        executor.shutdown();
    }

    interface Backend {
        Object open(Path model) throws Exception;

        String transcribe(Object context, float[] samples16k, String language);

        void close(Object context);
    }

    private static final class JniBackend implements Backend {
        private static boolean libraryLoaded;

        @Override
        public Object open(Path model) throws IOException {
            loadLibrary();
            WhisperJNI whisper = NativeAudioGuard.createClientNative(WhisperJNI::new);
            WhisperContext context = whisper.init(model);
            return context == null ? null : new Session(whisper, context);
        }

        @Override
        public String transcribe(Object activeContext, float[] samples16k, String language) {
            Session session = (Session) activeContext;
            WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
            params.printProgress = false;
            params.printRealtime = false;
            params.printTimestamps = false;
            params.nThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            params.language = language;
            int result = session.whisper().full(session.context(), params, samples16k, samples16k.length);
            if (result != 0) {
                throw new IllegalStateException("whisper returned " + result);
            }
            StringBuilder text = new StringBuilder();
            int segments = session.whisper().fullNSegments(session.context());
            for (int i = 0; i < segments; i++) {
                text.append(session.whisper().fullGetSegmentText(session.context(), i));
            }
            return text.toString().strip();
        }

        @Override
        public void close(Object activeContext) {
            Session session = (Session) activeContext;
            session.whisper().free(session.context());
        }

        private static synchronized void loadLibrary() throws IOException {
            if (!libraryLoaded) {
                NativeAudioGuard.requireClientEnvironment();
                WhisperJNI.loadLibrary();
                libraryLoaded = true;
            }
        }
    }

    private record Session(WhisperJNI whisper, WhisperContext context) {}
}
