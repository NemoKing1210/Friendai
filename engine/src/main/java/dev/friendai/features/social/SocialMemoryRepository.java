package dev.friendai.features.social;

import java.util.UUID;

/** Owner-scoped persistence port for the director's raised subjects and learned taste. */
public interface SocialMemoryRepository {
    /** Keeps session-only state, for runtimes composed without a durability store. */
    SocialMemoryRepository NONE = new SocialMemoryRepository() {
        @Override
        public SocialMemorySave state(UUID owner) {
            return SocialMemorySave.EMPTY;
        }

        @Override
        public void save(UUID owner, SocialMemorySave value) {}
    };

    /** The owner's saved state; a missing, corrupt or newer record is {@link SocialMemorySave#EMPTY}. */
    SocialMemorySave state(UUID owner);

    /** Replaces the owner's saved state; an unchanged snapshot writes nothing. */
    void save(UUID owner, SocialMemorySave value);
}
