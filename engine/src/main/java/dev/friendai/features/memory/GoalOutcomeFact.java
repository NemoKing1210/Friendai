package dev.friendai.features.memory;

/**
 * Builds the one durable memory line a finished or abandoned goal contributes: the goal sentence, whether the
 * world checked every checkable criterion, and the shortest verified fact. Pure Java: the caller owns the
 * write, its time and its category.
 *
 * <p>The line never exceeds {@link #MAX_TEXT} characters (or the book's smaller limit), and it keeps the
 * verdict even when the goal has to be shortened. An abandoned goal with nothing verified contributes nothing,
 * so a failure the world never observed is not remembered as work.
 */
public final class GoalOutcomeFact {
    /** The longest line this helper produces unless the book's limit is smaller. */
    public static final int MAX_TEXT = 160;

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
        String sentence = clean(goal);
        if (sentence.isEmpty() || end == null) {
            return null;
        }
        String fact = clean(verifiedFact);
        if (end == End.ABANDONED && fact.isEmpty()) {
            return null;
        }
        int max = Math.max(1, Math.min(MAX_TEXT, maxChars));
        String head = "goal \"";
        String tail = "\" \u2014 " + end.word + (fact.isEmpty() ? "" : "; " + fact);
        int room = max - head.length() - tail.length();
        if (room < 1) {
            // The evidence is optional; the verdict is not. A tight limit drops the fact before the goal.
            tail = "\" \u2014 " + end.word;
            room = max - head.length() - tail.length();
        }
        if (room < 1) {
            return clip(head + end.word, max);
        }
        return head + clip(sentence, room) + tail;
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
        return max < 2 ? text.substring(0, max) : text.substring(0, max - 1) + "\u2026";
    }
}
