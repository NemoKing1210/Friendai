package dev.friendai.features.inventory.minecraft.storage;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.storage.StoredCandidate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Server-thread access to the owner's observed-container index, as tools and tasks see it. */
public interface StorageAccess {
    void forget(CompanionEntity companion, ServerLevel level, BlockPos position);

    List<StoredCandidate> find(CompanionEntity companion, Set<String> itemIds, int minCount, long nowTick);

    Optional<BlockPos> takeTarget(CompanionEntity companion, Set<String> itemIds, int minCount);

    Optional<BlockPos> storeTarget(CompanionEntity companion);

    Set<String> itemIds(String idOrTag);

    long nowTick(CompanionEntity companion);

    /** Catalog-only implementation: records nothing and finds nothing. */
    StorageAccess UNAVAILABLE = new StorageAccess() {
        @Override
        public void forget(CompanionEntity companion, ServerLevel level, BlockPos position) {}

        @Override
        public List<StoredCandidate> find(
                CompanionEntity companion, Set<String> itemIds, int minCount, long nowTick) {
            return List.of();
        }

        @Override
        public Optional<BlockPos> takeTarget(CompanionEntity companion, Set<String> itemIds, int minCount) {
            return Optional.empty();
        }

        @Override
        public Optional<BlockPos> storeTarget(CompanionEntity companion) {
            return Optional.empty();
        }

        @Override
        public Set<String> itemIds(String idOrTag) {
            return StorageIndexService.ids(idOrTag);
        }

        @Override
        public long nowTick(CompanionEntity companion) {
            return companion == null ? 0 : companion.level().getGameTime();
        }
    };
}
