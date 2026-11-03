package dev.friendai.features.storage;

import java.util.UUID;

/** Owner-scoped persistence for the observed-container index. */
public interface StorageRepository {
    /** The saved index, or an empty index when the owner has no record yet. */
    StorageIndex index(UUID owner);

    /** Replaces the owner's index; an unchanged snapshot may write nothing. */
    void save(UUID owner, StorageIndex index);

    /** Session-only implementation for runtimes composed without durability. */
    StorageRepository NONE = new StorageRepository() {
        @Override
        public StorageIndex index(UUID owner) {
            return new StorageIndex();
        }

        @Override
        public void save(UUID owner, StorageIndex index) {}
    };
}
