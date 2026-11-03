package dev.friendai.client.voice;

import java.util.function.Consumer;

/**
 * A recognizer that can consume audio while it is being recorded.
 *
 * <p>Clip-only recognizers implement only {@link SpeechToText}; a caller that finds this capability starts a
 * session, feeds ordered 16 kHz mono samples, and listens for replaceable partial text. The final transcript is
 * produced exactly once by {@link StreamingSession#finish()}; cancelling never produces a turn.
 */
public interface StreamingSpeechToText extends SpeechToText {
    /**
     * Whether this recognizer may open a session at all. One adapter serves both model families, and a model whose
     * graph has no streaming encoder must be transcribed as a clip: asking its runtime for a session ends the process
     * instead of failing, so a caller checks this before calling {@link #startStreaming}.
     */
    boolean supportsStreaming();

    /**
     * Starts one streaming session. The session may load its native model on a worker, so this call returns quickly
     * and the first {@link StreamingSession#feed(float[])} is safe immediately.
     *
     * @param partials called from a worker with a replaceable partial transcript; may be null
     */
    StreamingSession startStreaming(String language, Consumer<String> partials);

    /** One push-to-talk utterance. Instances are single-use. */
    interface StreamingSession {
        /** Queues the next ordered chunk; throws a user-facing failure when the bounded buffer overflows. */
        void feed(float[] samples16k);

        /** Requests the final transcript; idempotent, returns the same future on every call. */
        java.util.concurrent.CompletableFuture<String> finish();

        /** Drops the session without producing a transcript; safe to call more than once. */
        void cancel();
    }
}
