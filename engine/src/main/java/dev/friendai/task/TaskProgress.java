package dev.friendai.task;

/**
 * One bounded, model-facing step of a task's lifecycle. Immutable and pure: the constructor normalizes every field so a
 * caller cannot publish a detail that floods a prompt, a multi-line detail that breaks a prompt line, or a percent
 * outside the contract.
 *
 * @param percent the task's measured completion in 0..100, or -1 when the task cannot measure itself
 * @param detail one concise English line for the owner and the model, never longer than {@link #MAX_DETAIL}
 */
public record TaskProgress(int taskId, String tool, TaskPhase phase, int percent, String detail) {
    /** The longest detail a prompt line may carry. */
    public static final int MAX_DETAIL = 120;

    private static final char ELLIPSIS = '\u2026';

    public TaskProgress {
        if (phase == null) throw new IllegalArgumentException("Task progress needs a phase.");
        tool = tool == null ? "" : tool;
        percent = Math.max(-1, Math.min(100, percent));
        detail = bounded(detail);
    }

    /** Whether {@link #percent()} is a real measurement rather than the "unknown" marker. */
    public boolean percentKnown() {
        return percent >= 0;
    }

    /**
     * Collapses a detail to one line of at most {@link #MAX_DETAIL} characters: line breaks and tabs become single
     * spaces, and truncation marks itself with an ellipsis instead of splitting a surrogate pair. Returns the same
     * string when it already fits, so the common case allocates nothing.
     */
    static String bounded(String text) {
        if (text == null || text.isEmpty()) return "";
        int length = text.length();
        boolean fits = length <= MAX_DETAIL;
        for (int i = 0; i < length && fits; i++) {
            if (isBreak(text.charAt(i))) fits = false;
        }
        if (fits) return text;
        StringBuilder out = new StringBuilder(Math.min(length, MAX_DETAIL));
        int limit = Math.min(length, MAX_DETAIL - 1);
        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i);
            if (isBreak(c)) {
                if (out.isEmpty() || out.charAt(out.length() - 1) != ' ') out.append(' ');
                continue;
            }
            out.append(c);
        }
        if (length > MAX_DETAIL) {
            if (Character.isHighSurrogate(out.charAt(out.length() - 1))) out.setLength(out.length() - 1);
            out.append(ELLIPSIS);
        }
        return out.toString();
    }

    private static boolean isBreak(char c) {
        return c == '\n' || c == '\r' || c == '\t';
    }
}
