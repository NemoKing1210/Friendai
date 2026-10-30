package dev.friendai.features.memory.transcript;

import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import java.util.UUID;

/** Who may read or clear a transcript: the owner everything, a trusted player their own, a guest nothing. */
public final class TranscriptPolicy {
    private TranscriptPolicy() {}

    public static boolean canRead(UUID owner, Caller caller, UUID player) {
        if (owner == null || caller == null || player == null) {
            return false;
        }
        return switch (caller.access()) {
            case OWNER -> true;
            case TRUSTED -> caller.uuid() != null && caller.uuid().equals(player);
            case GUEST -> false;
        };
    }

    /** Clearing is an owner action; a trusted player may clear only their own window. */
    public static boolean canClear(UUID owner, Caller caller, UUID player) {
        return canRead(owner, caller, player) && caller.access() == AccessLevel.OWNER;
    }
}
