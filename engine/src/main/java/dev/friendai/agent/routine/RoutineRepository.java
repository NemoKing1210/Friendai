package dev.friendai.agent.routine;

import java.util.Optional;
import java.util.UUID;

/** One owner's routine, or none. Implementations are independent owner records, never a shared store. */
public interface RoutineRepository {
    Optional<Routine> state(UUID owner);

    void save(UUID owner, Routine value);

    void clear(UUID owner);
}
