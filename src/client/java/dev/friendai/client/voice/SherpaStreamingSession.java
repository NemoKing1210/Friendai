package dev.friendai.client.voice;

import dev.friendai.features.speech.TranscriptText;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One streaming utterance: a bounded queue of 16 kHz chunks drained by a single worker that owns the native stream.
 *
 * <p>The microphone thread only offers a chunk, so a slow decoder never blocks capture. The queue holds
 * {@link #MAX_QUEUED_CHUNKS} chunks, roughly eight seconds; a full queue is a typed recording failure rather than
 * silent audio loss. Partials are capped by code points and published only when they change; the final transcript
 * is completed exactly once. Cancelling discards the queue and never produces a turn.
 */
final class SherpaStreamingSession implements StreamingSpeechToText.StreamingSession {
    static final int MAX_QUEUED_CHUNKS = 250;
    static final int MAX_PARTIAL_CHARS = 240;

    private static final long POLL_MILLIS = 20;

    private final BlockingQueue<float[]> chunks = new ArrayBlockingQueue<>(MAX_QUEUED_CHUNKS);
    private final ExecutorService executor;
    private final Supplier<SherpaStt.StreamEngine> engineFactory;
    private final Consumer<String> partials;
    private final Runnable onDone;
    private final CompletableFuture<String> result = new CompletableFuture<>();
    private final AtomicBoolean finishRequested = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile String lastPartial = "";

    SherpaStreamingSession(
            ExecutorService executor,
            Supplier<SherpaStt.StreamEngine> engineFactory,
            Consumer<String> partials,
            Runnable onDone) {
        this.executor = executor;
        this.engineFactory = engineFactory;
        this.partials = partials;
        this.onDone = onDone;
        try {
            executor.execute(this::run);
        } catch (RejectedExecutionException stopped) {
            result.completeExceptionally(SherpaStt.noModel());
        }
    }

    @Override
    public void feed(float[] samples16k) {
        if (samples16k == null || samples16k.length == 0) {
            return;
        }
        if (cancelled.get() || finishRequested.get() || result.isDone()) {
            throw SherpaStt.failed();
        }
        if (!chunks.offer(samples16k)) {
            throw new UserFacingException(UserMessage.of("friendai.voice.error.overflow"));
        }
    }

    @Override
    public CompletableFuture<String> finish() {
        finishRequested.set(true);
        return result;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        chunks.clear();
        result.complete("");
    }

    boolean isDone() {
        return result.isDone();
    }

    private void run() {
        SherpaStt.StreamEngine engine = null;
        try {
            if (cancelled.get()) {
                return;
            }
            engine = engineFactory.get();
            while (!cancelled.get()) {
                float[] chunk = chunks.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (chunk != null) {
                    engine.accept(chunk);
                    publish(engine.partial());
                } else if (finishRequested.get() && chunks.isEmpty()) {
                    break;
                }
            }
            if (!cancelled.get()) {
                // A chunk can arrive between the last poll and the finish flag; it is still part of the utterance.
                float[] rest;
                while ((rest = chunks.poll()) != null) {
                    engine.accept(rest);
                }
                String text = engine.finish();
                result.complete(text == null ? "" : text.strip());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result.completeExceptionally(SherpaStt.failed());
        } catch (Throwable error) {
            result.completeExceptionally(error);
        } finally {
            if (engine != null) {
                engine.close();
            }
            if (onDone != null) {
                onDone.run();
            }
        }
    }

    private void publish(String text) {
        String capped = TranscriptText.cap(text, MAX_PARTIAL_CHARS);
        if (capped.equals(lastPartial)) {
            return;
        }
        lastPartial = capped;
        if (partials != null) {
            try {
                partials.accept(capped);
            } catch (RuntimeException ignored) {
                // A failing display must never stop recognition.
            }
        }
    }
}
