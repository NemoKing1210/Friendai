package dev.friendai.features.social;

import java.util.Locale;

/**
 * The owner's chosen way of talking: how much the companion says and how much of it is unprompted.
 *
 * <p>It changes only the manner of a line, never what the companion knows, may do or is allowed to say. Every
 * clause says so in as many words, so the model cannot read a style as a permission. {@link #parse(String)} is
 * total: a hand-edited config file can never break a turn.
 */
public enum InteractionStyle {
    CONCISE("Speak briefly: short answers, only the detail asked for, and no unprompted narration;"
            + " this never changes permissions, facts or safety rules."),
    BALANCED("Speak naturally: a few sentences when useful, and narrate unprompted only when it matters;"
            + " this never changes permissions, facts or safety rules."),
    EXPRESSIVE("Speak warmly: more detail, small reactions and light unprompted narration;"
            + " this never changes permissions, facts or safety rules.");

    /** Long enough for one clause; short enough that it cannot crowd out the speech rules. */
    public static final int MAX_CLAUSE_CHARS = 160;

    private final String clause;

    InteractionStyle(String clause) {
        this.clause = clause;
    }

    /** The one prompt clause for this style, and nothing else around it. */
    public String clause() {
        return clause;
    }

    /** The value stored in friendai-server.json. */
    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The style a stored value names, or null when it is missing or is not one of the three names. */
    public static InteractionStyle fromName(String stored) {
        if (stored == null) {
            return null;
        }
        String text = stored.strip();
        for (InteractionStyle style : values()) {
            if (style.name().equalsIgnoreCase(text)) {
                return style;
            }
        }
        return null;
    }

    /** Always answers: an unknown or missing stored value is {@link #BALANCED}, never an exception. */
    public static InteractionStyle parse(String stored) {
        InteractionStyle style = fromName(stored);
        return style == null ? BALANCED : style;
    }

    /**
     * The social block with this style's clause appended. A blank block becomes the clause alone, so the
     * chosen style reaches the prompt exactly once, after whatever the bond line already said.
     */
    public static String appendTo(String stored, String social) {
        String clause = parse(stored).clause();
        return social == null || social.isBlank() ? clause : social + " " + clause;
    }
}
