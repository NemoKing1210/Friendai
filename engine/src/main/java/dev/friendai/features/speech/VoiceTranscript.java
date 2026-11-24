package dev.friendai.features.speech;

import java.nio.charset.StandardCharsets;

/**
 * What a recognized line is worth before anyone sends it. The wire limit is counted in UTF-8 bytes, not characters,
 * so a Cyrillic or emoji command reaches it long before 500 displayed characters; a line that does not fit is refused,
 * never shortened, because its last words can carry the constraint of the order.
 */
public final class VoiceTranscript {
    /** The speech request frame's limit for the transcript string: 500 UTF-8 bytes. */
    public static final int MAX_WIRE_BYTES = 500;

    public enum Readiness {
        EMPTY,
        TOO_LONG,
        READY
    }

    private VoiceTranscript() {}

    public static Readiness classify(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return Readiness.EMPTY;
        }
        return wireBytes(normalized) > MAX_WIRE_BYTES ? Readiness.TOO_LONG : Readiness.READY;
    }

    /** The text as it is sent: surrounding whitespace removed, never cut. */
    public static String normalize(String text) {
        return text == null ? "" : text.strip();
    }

    /** UTF-8 bytes the string occupies on the wire; malformed UTF-16 counts the encoded replacement form. */
    public static int wireBytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}
