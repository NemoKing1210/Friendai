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
import java.util.function.BooleanSupplier;

/**
 * Local speech recognition with whisper.cpp. The model is verified and opened on the recognizer's own worker on first
 * use, never on the calling thread: a large model must not stall a client tick, and a modified install must be refused
 * before native code sees it.
 */
public final class WhisperLocalStt implements SpeechToText, AutoCloseable {
    private final Path model;
    private final Backend backend;
    private final ExecutorService executor;
    private final BooleanSupplier verified;
    private Object context;
    private boolean closed;

    public WhisperLocalStt(Path model) {
        this(model, () -> true);
    }

    public WhisperLocalStt(Path model, BooleanSupplier verified) {
        this(
                model,
                new JniBackend(),
                Executors.newSingleThreadExecutor(r -> {
                    Thread thread = new Thread(r, "friendai-whisper");
                    thread.setDaemon(true);
                    return thread;
                }),
                verified);
    }

    WhisperLocalStt(Path model, Backend backend, ExecutorService executor) {
        this(model, backend, executor, () -> true);
    }

    WhisperLocalStt(Path model, Backend backend, ExecutorService executor, BooleanSupplier verified) {
        this.model = Objects.requireNonNull(model, "model");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.verified = Objects.requireNonNull(verified, "verified");
    }

    @Override
    public synchronized boolean isReady() {
        return !closed;
    }

    @Override
    public CompletableFuture<String> transcribe(float[] samples16k, String language) {
        synchronized (this) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("speech recognition is not ready"));
            }
        }
        String activeLanguage = language == null || language.isBlank() ? "auto" : language;
        return CompletableFuture.supplyAsync(
                () -> {
                    Object activeContext = prepare();
                    if (activeContext == null) {
                        throw new IllegalStateException("speech recognition is not ready");
                    }
                    try {
                        return backend.transcribe(activeContext, samples16k, activeLanguage);
                    } finally {
                        closeIfClosedWithPendingWork();
                    }
                },
                executor);
    }

    /** Work accepted before a disconnect still finishes; a context opened for it is then closed instead of leaked. */
    private synchronized void closeIfClosedWithPendingWork() {
        if (!closed || context == null) {
            return;
        }
        Object closing = context;
        context = null;
        try {
            backend.close(closing);
        } catch (RuntimeException | UnsatisfiedLinkError failure) {
            FriendaiMod.LOGGER.warn("Could not close local speech recognition", failure);
        }
    }

    /** Runs on the worker: verify once, then open once. */
    private synchronized Object prepare() {
        if (context != null) {
            return context;
        }
        if (!verified.getAsBoolean()) {
            FriendaiMod.LOGGER.warn("The local speech model failed verification; not loading it");
            return null;
        }
        try {
            context = backend.open(model);
        } catch (Exception | UnsatisfiedLinkError failure) {
            FriendaiMod.LOGGER.warn("Could not start local speech recognition", failure);
        }
        return context;
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
