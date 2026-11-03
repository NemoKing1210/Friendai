package dev.friendai.client.voice;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import dev.friendai.FriendaiMod;
import dev.friendai.features.speech.wake.WakeModelCatalog;
import dev.friendai.features.speech.wake.WakeModelCatalog.WakeModel;
import dev.friendai.features.speech.wake.WakeModelCatalog.WakePhrase;
import dev.friendai.foundation.text.UserMessage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * The local wake-word detector: one sherpa-onnx {@link KeywordSpotter} and one stream, fed the same 16 kHz microphone
 * audio the recognizer would get.
 *
 * <p>The native model is opened lazily on a worker thread, never on the client thread that renders the HUD or on the
 * capture thread that feeds audio, and the bundle is re-hashed before the native library sees it. The stream runs
 * continuously while listening so the detector keeps its acoustic context between chunks; a hit resets the stream and
 * the keyword is compared with the configured phrase, so the other phrases the bundle carries never open the
 * microphone. Audio is decoded in memory only — this class has no network and no file output beyond reading the
 * installed bundle.
 */
public final class SherpaKeywordDetector implements WakeWordHandler.Detector {
    /** The zipformer KWS model is tiny; one thread leaves the client thread's budget alone. */
    private static final int NUM_THREADS = 1;

    /** Beam and boosting values from the sherpa-onnx KWS defaults: four active paths, one trailing blank. */
    private static final int MAX_ACTIVE_PATHS = 4;

    private static final int NUM_TRAILING_BLANKS = 1;
    private static final float KEYWORDS_SCORE = 1.0f;
    private static final float KEYWORDS_THRESHOLD = 0.25f;

    private final WakeModel model;
    private final WakeBundleManager bundles;
    private final ExecutorService worker;
    private final BooleanSupplier verified;
    /** The keyword a hit must report to count, upper case like the bundle's token table. */
    private final String expectedKeyword;

    private final AtomicBoolean starting = new AtomicBoolean();
    private volatile boolean closed;
    private volatile UserMessage problem;

    private KeywordSpotter spotter;
    private OnlineStream stream;

    /** Production constructor: the catalogue's phrase travels into the bundle's own token keywords. */
    public SherpaKeywordDetector(WakeModel model, WakeBundleManager bundles, String phrase, ExecutorService worker) {
        this(model, bundles, phrase, worker, () -> bundles.verifyInstalled(model));
    }

    /** Test seam: a scripted verifier so no bundle has to exist. */
    SherpaKeywordDetector(
            WakeModel model,
            WakeBundleManager bundles,
            String phrase,
            ExecutorService worker,
            BooleanSupplier verified) {
        this.model = model;
        this.bundles = bundles;
        this.worker = worker;
        this.verified = verified;
        WakePhrase chosen = model.phrase(phrase).orElse(model.defaultPhrase());
        this.expectedKeyword = chosen.keyword();
    }

    @Override
    public void prepare() {
        if (!starting.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.execute(this::open);
        } catch (RejectedExecutionException alreadyClosed) {
            starting.set(false);
        }
    }

    @Override
    public boolean hit(float[] samples16k) {
        KeywordSpotter spotter;
        OnlineStream stream;
        synchronized (this) {
            spotter = this.spotter;
            stream = this.stream;
        }
        if (spotter == null || stream == null) {
            // The model is still loading (or unavailable): audio is dropped rather than queued, because only the
            // post-wake utterance is ever transcribed.
            prepare();
            return false;
        }
        if (samples16k == null || samples16k.length == 0) {
            return false;
        }
        synchronized (this) {
            if (closed || spotter != this.spotter || stream != this.stream) {
                return false;
            }
            try {
                stream.acceptWaveform(samples16k, WakeModelCatalog.SAMPLE_RATE);
                while (spotter.isReady(stream)) {
                    spotter.decode(stream);
                }
                KeywordSpotterResult result = spotter.getResult(stream);
                String keyword = result == null ? "" : result.getKeyword();
                if (keyword == null || keyword.isBlank()) {
                    return false;
                }
                // Reset right after a detection, as the sherpa-onnx example does, so the next phrase can be spotted.
                spotter.reset(stream);
                return keyword.strip().equalsIgnoreCase(expectedKeyword);
            } catch (RuntimeException | LinkageError failure) {
                abandoned(failure);
                return false;
            }
        }
    }

    @Override
    public UserMessage problem() {
        return problem;
    }

    @Override
    public void close() {
        KeywordSpotter closingSpotter;
        OnlineStream closingStream;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            closingSpotter = spotter;
            closingStream = stream;
            spotter = null;
            stream = null;
        }
        Runnable release = () -> releaseNative(closingSpotter, closingStream);
        try {
            worker.execute(release);
        } catch (RejectedExecutionException alreadyStopped) {
            release.run();
        }
        worker.shutdown();
    }

    /** Opens the native spotter on the worker; the stream is created from the bundle's keywords file. */
    private void open() {
        if (closed) {
            return;
        }
        try {
            if (!verified.getAsBoolean()) {
                // The bundle is not on disk yet: an explicit arm is also the download instruction, so the first
                // listening session installs it and the following ones adopt the verified files.
                bundles.install(model).join();
            }
            if (!verified.getAsBoolean()) {
                problem = UserMessage.of("friendai.voice.error.no_model");
                return;
            }
            KeywordSpotter createdSpotter = new KeywordSpotter(config());
            OnlineStream createdStream;
            try {
                createdStream = createdSpotter.createStream();
            } catch (RuntimeException | LinkageError failure) {
                createdSpotter.release();
                throw failure;
            }
            synchronized (this) {
                if (closed) {
                    releaseNative(createdSpotter, createdStream);
                    return;
                }
                spotter = createdSpotter;
                stream = createdStream;
            }
        } catch (RuntimeException | LinkageError failure) {
            FriendaiMod.LOGGER.warn("Could not start the wake word detector for {}", model.id(), failure);
            problem = userMessageOf(failure);
        }
    }

    /** Keeps the installer's own message (no connection, corrupt file) instead of a generic "no model". */
    private static UserMessage userMessageOf(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        if (cause instanceof dev.friendai.foundation.text.UserFacingException userFacing) {
            return userFacing.userMessage();
        }
        return UserMessage.of("friendai.voice.error.no_model");
    }

    private void abandoned(Throwable failure) {
        FriendaiMod.LOGGER.warn("The wake word detector stopped", failure);
        KeywordSpotter closingSpotter;
        OnlineStream closingStream;
        synchronized (this) {
            closingSpotter = spotter;
            closingStream = stream;
            spotter = null;
            stream = null;
            problem = UserMessage.of("friendai.voice.error.failed");
        }
        releaseNative(closingSpotter, closingStream);
    }

    private KeywordSpotterConfig config() {
        OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(path(encoder()))
                .setDecoder(path(decoder()))
                .setJoiner(path(joiner()))
                .build();
        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(path(tokens()))
                .setNumThreads(NUM_THREADS)
                .setDebug(false)
                .build();
        return KeywordSpotterConfig.builder()
                .setFeatureConfig(FeatureConfig.builder()
                        .setSampleRate(WakeModelCatalog.SAMPLE_RATE)
                        .setFeatureDim(80)
                        .build())
                .setOnlineModelConfig(modelConfig)
                .setKeywordsFile(bundles.dir(model)
                        .resolve(WakeModelCatalog.KEYWORDS_FILE)
                        .toString())
                .setMaxActivePaths(MAX_ACTIVE_PATHS)
                .setNumTrailingBlanks(NUM_TRAILING_BLANKS)
                .setKeywordsScore(KEYWORDS_SCORE)
                .setKeywordsThreshold(KEYWORDS_THRESHOLD)
                .build();
    }

    private String path(String file) {
        return bundles.dir(model).resolve(file).toString();
    }

    private String encoder() {
        return role("encoder");
    }

    private String decoder() {
        return role("decoder");
    }

    private String joiner() {
        return role("joiner");
    }

    private String tokens() {
        return role("tokens.txt");
    }

    private String role(String role) {
        return model.files().stream()
                .map(WakeModelCatalog.WakeFile::file)
                .filter(file -> file.contains(role))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("wake bundle has no " + role));
    }

    private static void releaseNative(KeywordSpotter spotter, OnlineStream stream) {
        try {
            if (stream != null) {
                stream.release();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A released stream only needs to stop holding the native handle.
        }
        try {
            if (spotter != null) {
                spotter.release();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Same for the spotter: a double release would be the only way to fail here.
        }
    }
}
