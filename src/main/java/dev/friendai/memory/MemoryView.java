package dev.friendai.memory;

import java.util.List;
import java.util.Optional;

/** What the memory tab shows: the groups, the facts and the result of the last action. */
public record MemoryView(
        boolean enabled,
        MemoryLimits limits,
        List<Group> groups,
        List<FactRow> facts,
        Optional<MenuStatus> status) {
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
}
