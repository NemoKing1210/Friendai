package dev.friendai.agent.autonomy;

import java.util.UUID;

/** Owner-scoped persistence port for free-time state. */
public interface FreeTimeRepository {
    FreeTime state(UUID owner);

    void save(UUID owner, FreeTime state);
}
