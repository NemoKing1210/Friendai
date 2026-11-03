package dev.friendai.features.inventory.minecraft.storage;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.storage.ContainerRecord;
import dev.friendai.features.storage.StorageIndex;
import dev.friendai.features.storage.StorageKey;
import dev.friendai.features.storage.StorageLimits;
import dev.friendai.features.storage.StorageRepository;
import dev.friendai.features.storage.StorageTargets;
import dev.friendai.features.storage.StoredCandidate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Server-thread adapter over the owner's observed-container index. It never scans the world. */
public final class StorageIndexService implements StorageAccess {
    private final StorageRepository repository;
    private final Supplier<StorageLimits> limits;

    public StorageIndexService(StorageRepository repository, Supplier<StorageLimits> limits) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    @Override
    public void observe(CompanionEntity companion, ServerLevel level, BlockPos position, Container container) {
        UUID owner = companion.ownerUuid();
        if (owner == null || position == null || container == null) {
            return;
        }
        String dimension = dimension(level);
        ContainerRecord base = snapshot(dimension, kind(level, position), container, level.getGameTime());
        BlockPos canonical = canonicalChest(position, candidate -> isOtherHalf(level, position, candidate));
        ContainerRecord record = new ContainerRecord(
                new StorageKey(dimension, canonical.getX(), canonical.getY(), canonical.getZ()),
                base.kind(),
                base.lastSeenTick(),
                base.slotsUsed(),
                base.slotsTotal(),
                base.counts());
        StorageIndex index = repository.index(owner);
        index.observe(record);
        repository.save(owner, index);
    }

    @Override
    public void forget(CompanionEntity companion, ServerLevel level, BlockPos position) {
        UUID owner = companion.ownerUuid();
        if (owner == null || position == null) {
            return;
        }
        String dimension = dimension(level);
        BlockPos canonical = canonicalChest(position, candidate -> isOtherHalf(level, position, candidate));
        StorageIndex index = repository.index(owner);
        index.forget(new StorageKey(dimension, canonical.getX(), canonical.getY(), canonical.getZ()));
        repository.save(owner, index);
    }

    @Override
    public List<StoredCandidate> find(
            CompanionEntity companion, Set<String> itemIds, int minCount, long nowTick) {
        StorageIndex index = book(companion);
        BlockPos position = companion.blockPosition();
        return StorageTargets.ordered(
                index.find(itemIds, minCount, nowTick),
                dimension(companion.level()),
                position.getX(),
                position.getY(),
                position.getZ());
    }

    @Override
    public Optional<BlockPos> takeTarget(CompanionEntity companion, Set<String> itemIds, int minCount) {
        BlockPos position = companion.blockPosition();
        return StorageTargets.take(
                        book(companion),
                        dimension(companion.level()),
                        position.getX(),
                        position.getY(),
                        position.getZ(),
                        itemIds,
                        minCount,
                        nowTick(companion),
                        limits.get())
                .map(record -> new BlockPos(
                        record.key().x(), record.key().y(), record.key().z()));
    }

    @Override
    public Optional<BlockPos> storeTarget(CompanionEntity companion) {
        BlockPos position = companion.blockPosition();
        return StorageTargets.store(
                        book(companion),
                        dimension(companion.level()),
                        position.getX(),
                        position.getY(),
                        position.getZ(),
                        nowTick(companion),
                        limits.get())
                .map(record -> new BlockPos(
                        record.key().x(), record.key().y(), record.key().z()));
    }

    @Override
    public Set<String> itemIds(String idOrTag) {
        return ids(idOrTag);
    }

    @Override
    public long nowTick(CompanionEntity companion) {
        return companion.level().getGameTime();
    }

    /** The owner's book; a companion without an owner gets a fresh in-memory index. */
    StorageIndex book(CompanionEntity companion) {
        UUID owner = companion.ownerUuid();
        return owner == null ? new StorageIndex(limits.get()) : repository.index(owner);
    }

    static ContainerRecord snapshot(String dimension, String kind, Container container, long gameTick) {
        int used = 0;
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            used++;
            counts.merge(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return new ContainerRecord(
                new StorageKey(dimension, 0, 0, 0),
                kind,
                gameTick,
                used,
                container.getContainerSize(),
                counts);
    }

    /**
     * The half of a double chest with the lower x, then the lower z; a single chest keeps its own position.
     */
    static BlockPos canonicalChest(BlockPos position, Predicate<BlockPos> otherHalfAt) {
        for (BlockPos candidate :
                List.of(position.west(), position.east(), position.north(), position.south())) {
            if (otherHalfAt.test(candidate)) {
                return lower(position, candidate);
            }
        }
        return position;
    }

    static Set<String> ids(String idOrTag) {
        if (idOrTag == null || idOrTag.isBlank()) {
            return Set.of();
        }
        String text = idOrTag.strip();
        boolean tag = text.startsWith("#");
        Identifier id = Identifier.tryParse(tag ? text.substring(1) : text);
        if (id == null) {
            return Set.of();
        }
        if (!tag) {
            return BuiltInRegistries.ITEM.containsKey(id) ? Set.of(id.toString()) : Set.of();
        }
        TagKey<Item> key = TagKey.create(Registries.ITEM, id);
        Set<String> found = new LinkedHashSet<>();
        for (var holder : BuiltInRegistries.ITEM.getTagOrEmpty(key)) {
            Item item = holder.value();
            found.add(BuiltInRegistries.ITEM.getKey(item).toString());
        }
        return Set.copyOf(found);
    }

    private static String dimension(net.minecraft.world.level.Level level) {
        return level.dimension().identifier().toString();
    }

    private static String kind(ServerLevel level, BlockPos position) {
        return BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(position).getBlock())
                .getPath();
    }

    private static boolean isOtherHalf(ServerLevel level, BlockPos origin, BlockPos candidate) {
        BlockState originState = level.getBlockState(origin);
        if (!(originState.getBlock() instanceof ChestBlock)) {
            return false;
        }
        if (!ChestBlock.getConnectedBlockPos(origin, originState).equals(candidate)) {
            return false;
        }
        BlockState candidateState = level.getBlockState(candidate);
        return candidateState.getBlock() instanceof ChestBlock
                && candidateState.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
    }

    private static BlockPos lower(BlockPos left, BlockPos right) {
        if (left.getX() != right.getX()) {
            return left.getX() < right.getX() ? left : right;
        }
        if (left.getZ() != right.getZ()) {
            return left.getZ() < right.getZ() ? left : right;
        }
        return left;
    }
}
