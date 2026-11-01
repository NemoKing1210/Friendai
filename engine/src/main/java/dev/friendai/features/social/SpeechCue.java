package dev.friendai.features.social;

import java.util.Locale;

/**
 * A closed list of things the owner can say that change the face. Matched on an explicit phrase, never inferred from a
 * promise, a question or a plain acknowledgement: a wrong guess at a feeling is worse than a calm face.
 */
public enum SpeechCue {
    NONE,
    GREETING,
    THANKS,
    APOLOGY,
    CALM_DOWN,
    CHALLENGE;

    private static final String[] PHRASES_GREETING = {
        "hello", "hi ", "hey", "good morning", "привет", "здравствуй", "доброе утро"
    };
    private static final String[] PHRASES_THANKS = {"thank", "thanks", "спасибо", "благодар"};
    private static final String[] PHRASES_APOLOGY = {"sorry", "apolog", "прости", "извини"};
    private static final String[] PHRASES_CALM_DOWN = {"calm down", "relax", "успокой", "не бойся"};
    private static final String[] PHRASES_CHALLENGE = {"let's go", "come on", "вперёд", "давай"};
    private static final int MAX_CHARS = 200;

    /** The cue in one short owner line, or {@link #NONE} when nothing explicit was said. */
    public static SpeechCue of(String text) {
        if (text == null || text.isBlank()) {
            return NONE;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_CHARS) {
            normalized = normalized.substring(0, MAX_CHARS);
        }
        if (matches(normalized, PHRASES_APOLOGY)) {
            return APOLOGY;
        }
        if (matches(normalized, PHRASES_THANKS)) {
            return THANKS;
        }
        if (matches(normalized, PHRASES_CALM_DOWN)) {
            return CALM_DOWN;
        }
        if (matches(normalized, PHRASES_GREETING)) {
            return GREETING;
        }
        return matches(normalized, PHRASES_CHALLENGE) ? CHALLENGE : NONE;
    }

    private static boolean matches(String text, String[] phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    /** The face this cue asks for, or {@code null} when the cue leaves the face alone. */
    public Mood mood() {
        return switch (this) {
            case GREETING -> Mood.PLEASED;
            case THANKS -> Mood.PLEASED;
            case APOLOGY -> Mood.DISPLEASED;
            case CALM_DOWN -> Mood.CALM;
            case CHALLENGE -> Mood.FOCUSED;
            case NONE -> null;
        };
    }
}
