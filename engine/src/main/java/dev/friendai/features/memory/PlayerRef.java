package dev.friendai.features.memory;

import java.util.UUID;

/** A player resolved by name, as the online-player lookup returns them. */
public record PlayerRef(UUID uuid, String name) {}
