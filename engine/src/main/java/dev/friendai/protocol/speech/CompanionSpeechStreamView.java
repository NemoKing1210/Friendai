package dev.friendai.protocol.speech;

import java.util.Objects;
import java.util.UUID;

/**
 * One ordered speech sentence of a streamed reply, addressed to one authenticated client. Identity is
 * {@code (streamId, sessionId, sequence)}; the client drops a sentence from another session, a duplicate sequence and
 * keeps {@code last} as the end of the stream. It carries the same audibility fields as a complete speech view.
 */
public record CompanionSpeechStreamView(
        String companion,
        String text,
        UUID addressee,
        UUID companionId,
        int audibleRange,
        long streamId,
        long sessionId,
        long sequence,
        boolean last) {
    public CompanionSpeechStreamView {
        Objects.requireNonNull(companion);
        Objects.requireNonNull(text);
        Objects.requireNonNull(addressee);
        Objects.requireNonNull(companionId);
        if (audibleRange < 0 || audibleRange > CompanionSpeechProtocolViewCodec.MAX_AUDIBLE_RANGE)
            throw new IllegalArgumentException("audible range out of protocol bounds");
        if (streamId < 1 || sessionId < 1 || sequence < 0) throw new IllegalArgumentException("stream identity");
    }
}
