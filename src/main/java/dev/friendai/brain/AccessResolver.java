package dev.friendai.brain;

import dev.friendai.tool.AccessLevel;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/** Maps a player to their rights with one companion: its owner, a trusted player, or a guest. */
public interface AccessResolver {
    UUID owner();

    AccessLevel accessOf(UUID player);

    static AccessResolver of(UUID owner, Predicate<UUID> trusted) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(trusted);
        return new AccessResolver() {
            @Override
            public UUID owner() {
                return owner;
            }

            @Override
            public AccessLevel accessOf(UUID player) {
                if (owner.equals(player)) {
                    return AccessLevel.OWNER;
                }
                return trusted.test(player) ? AccessLevel.TRUSTED : AccessLevel.GUEST;
            }
        };
    }
}
