package dev.friendai.memory;

import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import java.util.Objects;
import java.util.UUID;

/** The permission matrix for facts. The owner is the book's owner. */
public final class MemoryPolicy {
    private MemoryPolicy() {}

    public static boolean canWrite(UUID owner, Caller caller, Subject subject) {
        if (owner == null) {
            return false;
        }
        if (caller.access() == AccessLevel.OWNER) {
            return true;
        }
        if (subject instanceof Subject.World) {
            return caller.access() == AccessLevel.TRUSTED;
        }
        // An anonymous caller has no self: a null uuid must never match a null subject uuid.
        return caller.uuid() != null && Objects.equals(((Subject.Player) subject).uuid(), caller.uuid());
    }

    public static boolean canForget(UUID owner, Caller caller, Fact fact) {
        if (owner == null) {
            return false;
        }
        if (caller.access() == AccessLevel.OWNER) {
            return true;
        }
        UUID self = caller.uuid();
        if (self == null) {
            return false;
        }
        if (fact.subject() instanceof Subject.Player player && Objects.equals(player.uuid(), self)) {
            return true;
        }
        return caller.access() == AccessLevel.TRUSTED && Objects.equals(fact.authorUuid(), self);
    }

    public static boolean canRecall(UUID owner, Caller caller, UUID player) {
        if (owner == null) {
            return false;
        }
        if (caller.access() == AccessLevel.OWNER) {
            return true;
        }
        return caller.uuid() != null && Objects.equals(player, caller.uuid());
    }
}
