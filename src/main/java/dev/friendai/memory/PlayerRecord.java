package dev.friendai.memory;

import java.util.UUID;

/** What the companion knows about one player. Keyed by UUID, so a rename changes only the label. */
public record PlayerRecord(UUID uuid, String lastName, boolean trusted, long lastSeen) {}
