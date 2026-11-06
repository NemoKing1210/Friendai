package dev.friendai.server.agent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** The owner-to-agent-session map of one server. A lookup never creates; only {@link #getOrCreate} does. */
final class SessionRegistry {
    private final Map<UUID, CompanionSession> sessions = new HashMap<>();

    /** The owner's session, created empty when it does not exist yet. */
    CompanionSession getOrCreate(UUID owner, Function<UUID, CompanionSession> factory) {
        return sessions.computeIfAbsent(owner, factory);
    }

    CompanionSession get(UUID owner) {
        return sessions.get(owner);
    }

    /** A stable copy, safe to iterate while a nested call creates or removes a session. */
    List<CompanionSession> snapshot() {
        return List.copyOf(sessions.values());
    }

    void clear() {
        sessions.clear();
    }
}
