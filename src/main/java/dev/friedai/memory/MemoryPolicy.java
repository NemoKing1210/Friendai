package dev.friedai.memory;

import dev.friedai.tool.AccessLevel;
import dev.friedai.tool.Caller;
import java.util.Objects;
import java.util.UUID;

/** The permission matrix for facts. The owner is the book's owner. */
public final class MemoryPolicy {
    private MemoryPolicy() {}

    public static boolean canWrite(UUID owner, Caller caller, Subject subject) {
        if (caller.access() == AccessLevel.OWNER) {
            return true;
        }
        if (subject instanceof Subject.World) {
            return caller.access() == AccessLevel.TRUSTED;
        }
        return Objects.equals(((Subject.Player) subject).uuid(), self(owner, caller));
    }

    public static boolean canForget(UUID owner, Caller caller, Fact fact) {
        if (caller.access() == AccessLevel.OWNER) {
            return true;
        }
        UUID self = self(owner, caller);
        if (fact.subject() instanceof Subject.Player player && Objects.equals(player.uuid(), self)) {
            return true;
        }
        return caller.access() == AccessLevel.TRUSTED
                && caller.uuid() != null
                && Objects.equals(fact.authorUuid(), caller.uuid());
    }

    public static boolean canRecall(UUID owner, Caller caller, UUID player) {
        if (caller.access() == AccessLevel.OWNER) {
            return true;
        }
        return Objects.equals(player, self(owner, caller));
    }

    /** For an owner the "self" the matrix talks about is the owner, not the caller record. */
    private static UUID self(UUID owner, Caller caller) {
        return caller.access() == AccessLevel.OWNER ? owner : caller.uuid();
    }
}
