package dev.friendai.foundation.text;

import java.util.Optional;

/**
 * The owner's language as the companion learns it: the first confident sample is remembered, and a different
 * language must be seen {@link #SWITCH_AFTER} times in a row before it replaces the old one.
 *
 * <p>A message the detector cannot name changes nothing — a short "ok" or a quoted command is not evidence. An
 * explicit request is not a detection either: the prompt tells the model that the owner's request wins, so the
 * stored language only has to follow a sustained change.
 */
public final class LanguageMemory {
    /** How many consecutive samples in another language it takes to switch. */
    public static final int SWITCH_AFTER = 3;

    private String language;
    private String pending;
    private int pendingCount;

    private LanguageMemory(String language) {
        this.language = normalize(language);
    }

    /** A tracker that already knows a language, e.g. loaded from the owner's record. */
    public static LanguageMemory startingAt(String language) {
        return new LanguageMemory(language);
    }

    public Optional<String> language() {
        return Optional.ofNullable(language);
    }

    /**
     * Feeds one detected sample.
     *
     * @return the language that just became the stored one, or empty when nothing changed
     */
    public Optional<String> observe(Optional<String> detected) {
        if (detected == null || detected.isEmpty()) {
            return Optional.empty();
        }
        String next = normalize(detected.get());
        if (next == null) {
            return Optional.empty();
        }
        if (next.equalsIgnoreCase(language)) {
            pending = null;
            pendingCount = 0;
            return Optional.empty();
        }
        if (language == null) {
            language = next;
            pending = null;
            pendingCount = 0;
            return Optional.of(next);
        }
        if (!next.equalsIgnoreCase(pending)) {
            pending = next;
            pendingCount = 0;
        }
        pendingCount++;
        if (pendingCount < SWITCH_AFTER) {
            return Optional.empty();
        }
        language = next;
        pending = null;
        pendingCount = 0;
        return Optional.of(next);
    }

    private static String normalize(String language) {
        return language == null || language.isBlank() ? null : language.strip();
    }
}
