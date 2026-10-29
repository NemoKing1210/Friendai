package dev.friendai.features.memory;

import java.util.UUID;

/** Owner-scoped persistence port for durable memory facts and player records. */
public interface MemoryRepository {
    MemoryBook book(UUID owner);
}
