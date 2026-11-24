package dev.friendai.features.inventory.minecraft.storage;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.storage.ContainerRecord;
import dev.friendai.features.storage.StoredCandidate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Server-thread access to the owner's observed-container index, as tools and tasks see it. */
public interface StorageAccess {
    void forget(CompanionEntity companion, ServerLevel level, BlockPos position);

    List<ContainerRecord> records(CompanionEntity companion);

    List<StoredCandidate> find(CompanionEntity companion, Set<String> itemIds, int minCount, long nowTick);

    Optional<BlockPos> takeTarget(CompanionEntity companion, Set<String> itemIds, int minCount);

    /**
     * Where the carried items could go, best first, within {@code radius}. A counted free slot comes before a
     * container that only might take them, so a caller can try a short list and stop at the first that accepts.
     */
    List<BlockPos> storeTargets(CompanionEntity companion, int radius, Set<String> itemIds);

    Set<String> itemIds(String idOrTag);

    long nowTick(CompanionEntity companion);

    /** Catalog-only implementation: records nothing and finds nothing. */
    StorageAccess UNAVAILABLE = new StorageAccess() {
        @Override
        public void forget(CompanionEntity companion, ServerLevel level, BlockPos position) {}

        @Override
        public List<ContainerRecord> records(CompanionEntity companion) {
            return List.of();
        }

        @Override
        public List<StoredCandidate> find(CompanionEntity companion, Set<String> itemIds, int minCount, long nowTick) {
            return List.of();
        }

        @Override
        public Optional<BlockPos> takeTarget(CompanionEntity companion, Set<String> itemIds, int minCount) {
            return Optional.empty();
        }

        @Override
        public List<BlockPos> storeTargets(CompanionEntity companion, int radius, Set<String> itemIds) {
            return List.of();
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
