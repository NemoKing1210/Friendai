package dev.friendai.features.memory;

import java.util.Optional;

/** The personality the owner gives the companion: a short line of plain text that goes into the prompt. */
public final class PersonaText {
    public static final int MAX_LENGTH = 300;

    private PersonaText() {}

    /**
     * Trims the text and collapses runs of whitespace, or returns empty when it cannot be used. Formatting codes
     * and control characters are refused so the prompt line stays plain.
     */
    public static Optional<String> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.strip().replaceAll("\\s+", " ");
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' || Character.isISOControl(c)) {
                return Optional.empty();
            }
        }
        return Optional.of(text);
    }
}
