package dev.friendai.features.speech;

/** Text helpers shared by the HUD and the streaming recognizer. */
public final class TranscriptText {
    /** Appended when a transcript is shortened; it is display text, not part of the transcript. */
    public static final String ELLIPSIS = "\u2026";

    private TranscriptText() {}

    /**
     * Caps display text at {@code maxCodePoints} Unicode code points and appends an ellipsis when it was longer.
     * Counting code points keeps a surrogate pair (an emoji, a rare CJK character) from being split in half.
     */
    public static String cap(String text, int maxCodePoints) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        if (maxCodePoints <= 0) {
            return stripped.isEmpty() ? "" : ELLIPSIS;
        }
        int length = stripped.codePointCount(0, stripped.length());
        if (length <= maxCodePoints) {
            return stripped;
        }
        return stripped.substring(0, stripped.offsetByCodePoints(0, maxCodePoints)) + ELLIPSIS;
    }
}
