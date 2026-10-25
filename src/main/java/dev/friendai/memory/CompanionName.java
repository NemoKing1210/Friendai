package dev.friendai.memory;

import java.util.Optional;

/** The name the owner gives the companion: one short line of plain text, safe for the name tag and chat. */
public final class CompanionName {
    public static final int MAX_LENGTH = 32;
    /** Written for the model when a name cannot be used. */
    public static final String PROBLEM =
            "Use 1-" + MAX_LENGTH + " characters, without line breaks or formatting codes.";

    private CompanionName() {}

    /**
     * Trims the name and collapses runs of whitespace, or returns empty when it cannot be shown as a name.
     * Formatting codes and control characters are refused so the name tag and chat lines stay readable.
     */
    public static Optional<String> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String name = raw.strip().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '§' || Character.isISOControl(c)) {
                return Optional.empty();
            }
        }
        return Optional.of(name);
    }
}
