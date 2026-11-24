package dev.friendai.features.acquisition;

import dev.friendai.execution.settings.ResourceSearchSettings;
import java.util.Locale;
import java.util.Objects;

/**
 * The resource-search budget one acquisition call authorized: the requested scope resolved against the caller and the
 * server settings. Preview and execution resolve it through this one rule, so the refusal a trusted player gets and
 * the limits an owner gets are the same in both places.
 */
public record SearchScope(Kind kind, int radius, int ticks) {
    public enum Kind {
        LOCAL,
        EXPEDITION
    }

    public static final String LOCAL = "local";
    public static final String EXPEDITION = "expedition";

    /**
     * Resolves {@code requested} ({@code local} when blank) for a caller.
     *
     * @throws IllegalArgumentException with the message the caller sees, for an unknown scope or an expedition the
     *     caller may not run
     */
    public static SearchScope resolve(String requested, boolean owner, ResourceSearchSettings settings) {
        Objects.requireNonNull(settings, "settings");
        String value = requested == null || requested.isBlank()
                ? LOCAL
                : requested.strip().toLowerCase(Locale.ROOT);
        if (!value.equals(LOCAL) && !value.equals(EXPEDITION)) {
            throw new IllegalArgumentException("scope must be local or expedition");
        }
        if (value.equals(EXPEDITION)) {
            if (!owner || !settings.allowExpeditions() || !settings.chunkLoadingEnabled()) {
                throw new IllegalArgumentException("expeditions are disabled or owner-only");
            }
            return new SearchScope(Kind.EXPEDITION, settings.expeditionRadius(), settings.expeditionTicks());
        }
        return new SearchScope(Kind.LOCAL, settings.localRadius(), settings.localTicks());
    }

    public boolean expedition() {
        return kind == Kind.EXPEDITION;
    }

    /** The wire token the tasks and checkpoints carry. */
    public String token() {
        return expedition() ? EXPEDITION : LOCAL;
    }
}
