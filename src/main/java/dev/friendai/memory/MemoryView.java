package dev.friendai.memory;

import java.util.List;
import java.util.Optional;

/** What the memory tab shows: the groups, the facts, the companion's personality and the last action's result. */
public record MemoryView(
        boolean enabled,
        MemoryLimits limits,
        List<Group> groups,
        List<FactRow> facts,
        Optional<MenuStatus> status,
        PersonaView persona) {
    /** A view with no personality information, for callers that do not show it. */
    public MemoryView(
            boolean enabled,
            MemoryLimits limits,
            List<Group> groups,
            List<FactRow> facts,
            Optional<MenuStatus> status) {
        this(enabled, limits, groups, facts, status, new PersonaView("", false));
    }

    public enum Role {
        OWNER,
        WORLD,
        TRUSTED,
        GUEST
    }

    /** One group of facts: the owner, the world or another player. {@code key} is a UUID string or {@code world}. */
    public record Group(String key, String name, Role role, int count, boolean online) {}

    /** One fact row. {@code authorName} is empty when the owner wrote it. */
    public record FactRow(int id, String groupKey, Category category, String text, String authorName) {}

    /** The personality in effect: the owner's own when {@code custom}, otherwise the server default. */
    public record PersonaView(String text, boolean custom) {}
}
