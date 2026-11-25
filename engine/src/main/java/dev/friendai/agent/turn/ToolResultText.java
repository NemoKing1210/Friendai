package dev.friendai.agent.turn;

/**
 * Bounds one tool result before it enters the conversation history.
 *
 * <p>A result stays in the history for the rest of the turn and is re-sent with every later step, so one verbose
 * {@code queue_show} or {@code get_inventory} would multiply its cost by the number of steps left. The bound keeps the
 * beginning and the end — the summary a tool writes first and the verdict it writes last — and marks the gap with the
 * number of characters left out, so the model knows the text was cut instead of assuming it read everything.
 *
 * <p>Only the history copy is bounded. The result the observers record and the repeated-call guard compares stay whole.
 */
public final class ToolResultText {
    /** Characters kept from any single result; a longer one is cut in the middle. */
    public static final int MAX_CHARS = 4_000;

    private ToolResultText() {}

    public static String of(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        int head = MAX_CHARS / 2;
        int tail = MAX_CHARS - head;
        int headEnd = safeBoundary(text, head, false);
        int tailStart = safeBoundary(text, text.length() - tail, true);
        int omitted = tailStart - headEnd;
        return text.substring(0, headEnd)
                + "\n...[" + omitted + " characters omitted from this tool result]...\n"
                + text.substring(tailStart);
    }

    /**
     * Moves a cut off a surrogate pair: half of a pair decodes as a replacement character and counts against the
     * provider's tokens like any other.
     */
    private static int safeBoundary(String text, int index, boolean forward) {
        if (index <= 0) {
            return 0;
        }
        if (index >= text.length()) {
            return text.length();
        }
        boolean splitsPair =
                Character.isLowSurrogate(text.charAt(index)) && Character.isHighSurrogate(text.charAt(index - 1));
        if (!splitsPair) {
            return index;
        }
        return forward ? index + 1 : index - 1;
    }
}
