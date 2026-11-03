package dev.friendai.features.inventory.minecraft.storage;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.knowledge.ResourceKnowledge;
import dev.friendai.features.knowledge.ResourceObservation;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.storage.ContainerRecord;
import dev.friendai.features.storage.StorageIndex;
import dev.friendai.features.storage.StorageKey;
import dev.friendai.features.storage.StorageLimits;
import dev.friendai.features.storage.StorageTargets;
import dev.friendai.features.storage.StoredCandidate;
import dev.friendai.task.interact.ContainerAccess;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Builds the query view over the container observations the knowledge store already records after every
 * authorized interaction. It never scans the world and never writes a second record.
 */
public final class StorageIndexService implements StorageAccess {
    private final Function<UUID, ResourceKnowledge> books;
    private final Supplier<StorageLimits> limits;

    public StorageIndexService(Function<UUID, ResourceKnowledge> books, Supplier<StorageLimits> limits) {
        this.books = Objects.requireNonNull(books, "books");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    @Override
    public void forget(CompanionEntity companion, ServerLevel level, BlockPos position) {
        UUID owner = companion.ownerUuid();
        if (owner == null || position == null) {
            return;
        }
        BlockPos canonical = ContainerAccess.canonicalChest(level, position);
        books.apply(owner)
                .invalidateContainer(new ResourceObservation.Location(
                        level.dimension().identifier().toString(),
                        new Pos(canonical.getX(), canonical.getY(), canonical.getZ())));
    }

    @Override
    public List<ContainerRecord> records(CompanionEntity companion) {
        return List.copyOf(book(companion).records());
    }

    @Override
    public List<StoredCandidate> find(CompanionEntity companion, Set<String> itemIds, int minCount, long nowTick) {
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

    /** The owner's query view, built from the knowledge store's container observations. */
    StorageIndex book(CompanionEntity companion) {
        StorageIndex index = new StorageIndex(limits.get());
        UUID owner = companion.ownerUuid();
        if (owner == null) {
            return index;
        }
        for (ResourceKnowledge.ContainerObservation observation :
                books.apply(owner).containerObservations()) {
            index.observe(toRecord(observation));
        }
        return index;
    }

    /** Maps one knowledge observation to the query record; legacy slot-less rows read as unknown capacity. */
    static ContainerRecord toRecord(ResourceKnowledge.ContainerObservation observation) {
        int total = observation.slotsTotal() > 0 ? observation.slotsTotal() : 27;
        int used = observation.slotsTotal() > 0 ? Math.min(observation.slotsUsed(), total) : 0;
        return new ContainerRecord(
                new StorageKey(
                        observation.location().dimension(),
                        observation.location().pos().x(),
                        observation.location().pos().y(),
                        observation.location().pos().z()),
                observation.kind().isBlank() ? "container" : observation.kind(),
                observation.observedTick(),
                used,
                total,
                observation.items());
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
}
