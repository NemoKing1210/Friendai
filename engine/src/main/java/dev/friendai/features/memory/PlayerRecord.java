package dev.friendai.features.memory;

import java.util.UUID;

/** What the companion knows about one player. Keyed by UUID, so a rename changes only the label. */
public record PlayerRecord(UUID uuid, String lastName, boolean trusted, long lastSeen, String language) {
    /** A record of a player whose language has not been learned. */
    public PlayerRecord(UUID uuid, String lastName, boolean trusted, long lastSeen) {
        this(uuid, lastName, trusted, lastSeen, null);
    }

    public PlayerRecord {
        language = language == null || language.isBlank() ? null : language.strip();
    }
}
