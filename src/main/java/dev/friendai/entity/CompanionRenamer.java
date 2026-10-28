package dev.friendai.entity;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.features.memory.CompanionName;
import java.util.Optional;

/** The one place a companion is renamed, shared by the {@code set_name} tool and the companion menu. */
public final class CompanionRenamer {
    public enum Outcome {
        RENAMED,
        INVALID_NAME,
        NO_OWNER
    }

    /** {@code name} is the cleaned name when {@code outcome} is {@link Outcome#RENAMED}, otherwise null. */
    public record Result(Outcome outcome, String name) {}

    private CompanionRenamer() {}

    /** Stores the name in the owner's book, so it survives a dismiss, and shows it on the name tag. */
    public static Result rename(CompanionEntity companion, String raw) {
        Optional<String> parsed = CompanionName.parse(raw);
        if (parsed.isEmpty()) {
            return new Result(Outcome.INVALID_NAME, null);
        }
        if (companion.ownerUuid() == null) {
            return new Result(Outcome.NO_OWNER, null);
        }
        String name = parsed.get();
        FriendaiServerState.get(companion.level().getServer())
                .memory()
                .book(companion.ownerUuid())
                .setCompanionName(name);
        companion.applyName(name);
        return new Result(Outcome.RENAMED, name);
    }
}
