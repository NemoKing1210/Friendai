package dev.friendai.features.memory;

/**
 * Builds the one durable memory line a finished or abandoned goal contributes: the goal sentence, whether the
 * world checked every checkable criterion, and the shortest verified fact. Pure Java: the caller owns the
 * write, its time and its category.
 *
 * <p>The line never exceeds {@link #MAX_TEXT} characters (or the book's smaller limit), and it keeps the
 * verdict even when the goal has to be shortened. An abandoned goal contributes a line when there is evidence
 * or an explanation for the abandonment.
 */
public final class GoalOutcomeFact {
    /** The longest line this helper produces unless the book's limit is smaller. */
    public static final int MAX_TEXT = 160;

    private static final int MAX_REASON = 80;

    /** How a goal ended, judged by the world rather than by the model's own word. */
    public enum End {
        /** Every checkable criterion held when the goal ended. */
        VERIFIED("verified"),
        /** The goal ended without the world confirming every checkable criterion. */
        UNVERIFIED("unverified"),
        /** The goal was given up. */
        ABANDONED("abandoned, unverified");

        final String word;

        End(String word) {
            this.word = word;
        }
    }

    private GoalOutcomeFact() {}

    /**
     * @param goal the goal sentence
     * @param end how the goal ended
     * @param verifiedFact the shortest verified outcome fact, or null/blank when none exists
     * @param maxChars the book's fact limit
     * @return the fact text, or null when nothing durable should be written
     */
    public static String text(String goal, End end, String verifiedFact, int maxChars) {
        return text(goal, end, verifiedFact, "", maxChars);
    }

    public static String text(String goal, End end, String verifiedFact, String abandonmentReason, int maxChars) {
        String sentence = clean(goal);
        if (sentence.isEmpty() || end == null) {
            return null;
        }
        String fact = clean(verifiedFact);
        String reason = end == End.ABANDONED ? boundedReason(abandonmentReason) : "";
        if (end == End.ABANDONED && fact.isEmpty() && reason.isEmpty()) {
            return null;
        }
        int max = Math.max(1, Math.min(MAX_TEXT, maxChars));
        String head = "goal \"";
        String verdict = "\" \u2014 " + end.word;
        int room = max - head.length() - verdict.length();
        if (room < 1) {
            return clip(head + end.word, max);
        }
        String tail = verdict;
        String reasonLabel = "; reason: ";
        if (!reason.isEmpty() && room > reasonLabel.length() + 1) {
            String detail = reasonLabel + clip(reason, room - reasonLabel.length() - 1);
            tail += detail;
            room -= detail.length();
        }
        if (!fact.isEmpty() && room > fact.length() + 2) {
            tail += "; " + fact;
            room -= fact.length() + 2;
        }
        return head + clip(sentence, room) + tail;
    }

    /** Folds a redacted explanation to one bounded line. */
    public static String boundedReason(String redacted) {
        return clip(clean(redacted), MAX_REASON);
    }

    /** Squeezes whitespace and quotes so the sentence stays one readable memory line. */
    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.strip().replaceAll("\\s+", " ").replace('"', '\'');
    }

    /** Truncates to {@code max} characters, ending an over-long value with an ellipsis. */
    private static String clip(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        if (max == 1) {
            return Character.isHighSurrogate(text.charAt(0)) ? "\u2026" : text.substring(0, 1);
        }
        int end = max - 1;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "\u2026";
    }
}
