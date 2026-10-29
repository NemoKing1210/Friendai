package dev.friendai.protocol.speech;

import java.util.Objects;
import java.util.UUID;

/** A bounded speech event addressed to one authenticated client. */
public record CompanionSpeechView(String companion, String text, UUID addressee, UUID companionId, int audibleRange) {
    public CompanionSpeechView {
        Objects.requireNonNull(companion);
        Objects.requireNonNull(text);
        Objects.requireNonNull(addressee);
        Objects.requireNonNull(companionId);
        if (audibleRange < 0 || audibleRange > CompanionSpeechProtocolViewCodec.MAX_AUDIBLE_RANGE)
            throw new IllegalArgumentException("audible range out of protocol bounds");
    }
}
