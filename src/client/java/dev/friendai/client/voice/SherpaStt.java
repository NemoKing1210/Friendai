package dev.friendai.client.voice;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import dev.friendai.features.speech.stt.LocalSttCatalog.LocalSttModel;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Local recognition through the pinned sherpa-onnx runtime: an offline transducer (Parakeet, GigaAM) and an online
 * transducer with bounded partial output (Nemotron).
 *
 * <p>The native model is opened lazily on the engine's single worker thread, never on the client thread that builds a
 * screen or starts a recording. Before the first open, the injected verifier re-hashes the bundle, so a corrupt or
 * truncated installation fails as a user-facing "no model" instead of a native crash. Every native object is touched
 * only from that worker and is released exactly once when the engine closes.
 *
 * <p>A clip-only model ignores the language argument: Parakeet detects the language and GigaAM is Russian. Nemotron
 * receives the configured tag per stream ({@code auto} when unset) through the documented stream option.
 */
public final class SherpaStt implements StreamingSpeechToText, AutoCloseable {
    static final int SAMPLE_RATE = 16_000;
    static final int FEATURE_DIM = 80;
    static final int NUM_THREADS = 2;

    private final EngineFactory factory;
    private final ExecutorService executor;
    private final BooleanSupplier verified;
    private final LocalSttModel model;

    private volatile boolean closed;
    private boolean verifiedOnce;
    private ClipEngine clipEngine;
    private SherpaStreamingSession activeSession;

    /** Production constructor: builds sherpa-onnx configurations from the catalog entry. */
    public SherpaStt(LocalSttModel model, Path bundleDir, BooleanSupplier verified, ExecutorService executor) {
        this(new NativeFactory(model, bundleDir), executor, verified, model);
    }

    /** Test seam: a scripted factory with no native library. */
    SherpaStt(EngineFactory factory, ExecutorService executor, BooleanSupplier verified, LocalSttModel model) {
        this.factory = factory;
        this.executor = executor;
        this.verified = verified;
        this.model = model;
    }

    @Override
    public boolean isReady() {
        return !closed;
    }

    @Override
    public CompletableFuture<String> transcribe(float[] samples16k, String language) {
        if (samples16k == null || samples16k.length == 0) {
            return CompletableFuture.completedFuture("");
        }
        synchronized (this) {
            if (closed) {
                return CompletableFuture.failedFuture(noModel());
            }
        }
        return CompletableFuture.supplyAsync(
                () -> {
                    ensureVerified();
                    String text = clip().transcribe(samples16k);
                    return text == null ? "" : text.strip();
                },
                executor);
    }

    @Override
    public StreamingSession startStreaming(String language, Consumer<String> partials) {
        synchronized (this) {
            if (closed) {
                throw noModel();
            }
            if (activeSession != null) {
                activeSession.cancel();
            }
            SherpaStreamingSession session = new SherpaStreamingSession(
                    executor,
                    () -> {
                        ensureVerified();
                        return factory.openStream(effectiveLanguage(language));
                    },
                    partials,
                    () -> {
                        synchronized (SherpaStt.this) {
                            // Only clear the slot this session still owns; a newer press may have replaced it.
                            if (activeSession != null && activeSession.isDone()) {
                                activeSession = null;
                            }
                        }
                    });
            activeSession = session;
            return session;
        }
    }

    @Override
    public void close() {
        StreamingSession session;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            session = activeSession;
            activeSession = null;
        }
        if (session != null) {
            session.cancel();
        }
        try {
            executor.execute(this::releaseNative);
        } catch (RejectedExecutionException alreadyStopped) {
            releaseNative();
        }
        executor.shutdown();
    }

    private void releaseNative() {
        ClipEngine engine;
        synchronized (this) {
            engine = clipEngine;
            clipEngine = null;
        }
        if (engine != null) {
            engine.close();
        }
    }

    private synchronized ClipEngine clip() {
        if (clipEngine == null) {
            clipEngine = factory.openClip();
        }
        return clipEngine;
    }

    private void ensureVerified() {
        if (verifiedOnce) {
            return;
        }
        if (!verified.getAsBoolean()) {
            throw noModel();
        }
        verifiedOnce = true;
    }

    /** The tag a model that reads one should receive; {@code auto} when the setting is unset. */
    String effectiveLanguage(String language) {
        if (model == null || !model.languagePrompt()) {
            return null;
        }
        if (language == null || language.isBlank()) {
            return "auto";
        }
        String tag = language.strip().toLowerCase(Locale.ROOT);
        return tag.isEmpty() ? "auto" : tag;
    }

    static UserFacingException noModel() {
        return new UserFacingException(UserMessage.of("friendai.voice.error.no_model"));
    }

    static UserFacingException failed() {
        return new UserFacingException(UserMessage.of("friendai.voice.error.failed"));
    }

    /** One blocking offline decode. */
    interface ClipEngine extends AutoCloseable {
        String transcribe(float[] samples16k);

        @Override
        void close();
    }

    /** One blocking online stream; every call happens on the session's worker. */
    interface StreamEngine extends AutoCloseable {
        void accept(float[] samples16k);

        /** Decodes what is ready and returns the current replaceable transcript. */
        String partial();

        /** Flushes the remaining input and returns the final transcript. */
        String finish();

        @Override
        void close();
    }

    interface EngineFactory {
        ClipEngine openClip();

        StreamEngine openStream(String language);
    }

    /** sherpa-onnx-backed engines. Every native object is created, used and released on one worker thread. */
    private static final class NativeFactory implements EngineFactory {
        private final LocalSttModel model;
        private final Path dir;

        NativeFactory(LocalSttModel model, Path dir) {
            this.model = model;
            this.dir = dir;
        }

        @Override
        public ClipEngine openClip() {
            try {
                OfflineRecognizer recognizer = new OfflineRecognizer(offlineConfig());
                return new ClipEngine() {
                    @Override
                    public String transcribe(float[] samples16k) {
                        OfflineStream stream = recognizer.createStream();
                        try {
                            stream.acceptWaveform(samples16k, SAMPLE_RATE);
                            recognizer.decode(stream);
                            OfflineRecognizerResult result = recognizer.getResult(stream);
                            return result == null ? "" : result.getText();
                        } finally {
                            stream.release();
                        }
                    }

                    @Override
                    public void close() {
                        recognizer.release();
                    }
                };
            } catch (RuntimeException | LinkageError e) {
                throw nativeFailure(e);
            }
        }

        @Override
        public StreamEngine openStream(String language) {
            try {
                OnlineRecognizer recognizer = new OnlineRecognizer(onlineConfig());
                OnlineStream stream;
                try {
                    stream = recognizer.createStream();
                    if (language != null) {
                        stream.setOption("language", language);
                    }
                } catch (RuntimeException | LinkageError e) {
                    recognizer.release();
                    throw e;
                }
                return new StreamEngine() {
                    @Override
                    public void accept(float[] samples16k) {
                        stream.acceptWaveform(samples16k, SAMPLE_RATE);
                    }

                    @Override
                    public String partial() {
                        while (recognizer.isReady(stream)) {
                            recognizer.decode(stream);
                        }
                        return recognizer.getResult(stream).getText();
                    }

                    @Override
                    public String finish() {
                        stream.inputFinished();
                        while (recognizer.isReady(stream)) {
                            recognizer.decode(stream);
                        }
                        return recognizer.getResult(stream).getText();
                    }

                    @Override
                    public void close() {
                        try {
                            stream.release();
                        } finally {
                            recognizer.release();
                        }
                    }
                };
            } catch (RuntimeException | LinkageError e) {
                throw nativeFailure(e);
            }
        }

        private OfflineRecognizerConfig offlineConfig() {
            return OfflineRecognizerConfig.builder()
                    .setFeatureConfig(featureConfig())
                    .setOfflineModelConfig(OfflineModelConfig.builder()
                            .setTransducer(OfflineTransducerModelConfig.builder()
                                    .setEncoder(path(model.sherpa().encoder()))
                                    .setDecoder(path(model.sherpa().decoder()))
                                    .setJoiner(path(model.sherpa().joiner()))
                                    .build())
                            .setTokens(path(model.sherpa().tokens()))
                            .setModelType(model.modelType())
                            .setNumThreads(NUM_THREADS)
                            .setDebug(false)
                            .build())
                    .build();
        }

        private OnlineRecognizerConfig onlineConfig() {
            return OnlineRecognizerConfig.builder()
                    .setFeatureConfig(featureConfig())
                    .setOnlineModelConfig(OnlineModelConfig.builder()
                            .setTransducer(OnlineTransducerModelConfig.builder()
                                    .setEncoder(path(model.sherpa().encoder()))
                                    .setDecoder(path(model.sherpa().decoder()))
                                    .setJoiner(path(model.sherpa().joiner()))
                                    .build())
                            .setTokens(path(model.sherpa().tokens()))
                            .setModelType(model.modelType())
                            .setNumThreads(NUM_THREADS)
                            .setDebug(false)
                            .build())
                    .build();
        }

        private static FeatureConfig featureConfig() {
            return FeatureConfig.builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setFeatureDim(FEATURE_DIM)
                    .build();
        }

        private String path(String file) {
            return dir.resolve(file).toString();
        }

        private static UserFacingException nativeFailure(Throwable error) {
            if (error instanceof UserFacingException userFacing) {
                return userFacing;
            }
            return noModel();
        }
    }
}
