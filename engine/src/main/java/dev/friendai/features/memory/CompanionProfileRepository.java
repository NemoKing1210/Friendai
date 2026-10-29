package dev.friendai.features.memory;

import java.util.UUID;

/** Owner-scoped persistence port for companion identity and communication preferences. */
public interface CompanionProfileRepository {
    CompanionProfile profile(UUID owner);

    void save(UUID owner, CompanionProfile profile);

    /** Current in-memory revision; it changes on each profile mutation for view invalidation. */
    long revision(UUID owner);
}
