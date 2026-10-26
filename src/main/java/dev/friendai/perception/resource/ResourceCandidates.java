package dev.friendai.perception.resource;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.knowledge.KnowledgeStore;
import dev.friendai.knowledge.ResourceKnowledge;
import dev.friendai.knowledge.ResourceObservation;
import dev.friendai.knowledge.ResourceQuery;
import dev.friendai.knowledge.ResourceSite;
import dev.friendai.crafting.obtain.ResourceCandidate;
import dev.friendai.pathing.Pos;
import dev.friendai.task.mining.BlockMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Shared access to direct visible evidence and remembered resource sites. */
public final class ResourceCandidates {
    public enum Verification { PENDING, PRESENT, ABSENT, OCCLUDED, UNLOADED }

    private final KnowledgeStore knowledge;
    private final Map<UUID, List<ResourceObservation>> visibleNow = new HashMap<>();
    private ObservationScheduler observations;

    public ResourceCandidates(KnowledgeStore knowledge) { this.knowledge = knowledge; }

    public void attach(ObservationScheduler observations) { this.observations = observations; }

    public void observe(CompanionEntity companion, ResourceObservation observation) {
        UUID owner = companion.ownerUuid();
        if (owner == null) return;
        ResourceKnowledge book = knowledge.book(owner);
        book.observe(observation, observation.blockId());
        List<ResourceObservation> current = visibleNow.computeIfAbsent(owner, ignored -> new ArrayList<>());
        current.removeIf(previous -> previous.observedTick() != observation.observedTick());
        if (current.stream().noneMatch(previous -> previous.location().equals(observation.location()))) current.add(observation);
    }

    /** Returns only evidence observed in the current world tick and still locally loaded. */
    public List<BlockPos> visible(CompanionEntity companion, BlockMatcher matcher, int radius, int limit) {
        if (matcher == null || limit < 1) return List.of();
        return currentVisible(companion, radius).stream()
                .filter(observation -> matches(matcher, observation.blockId()))
                .map(observation -> new BlockPos(observation.location().pos().x(), observation.location().pos().y(),
                        observation.location().pos().z()))
                .limit(limit)
                .toList();
    }

    public Map<BlockPos, String> visibleBlockIds(CompanionEntity companion, int radius) {
        Map<BlockPos, String> result = new HashMap<>();
        currentVisible(companion, radius).forEach(observation -> {
            var pos = observation.location().pos();
            result.put(new BlockPos(pos.x(), pos.y(), pos.z()), observation.blockId());
        });
        return Map.copyOf(result);
    }

    /** Direct observations from the current tick, bounded to the companion's visible radius. */
    public List<ResourceObservation> currentVisible(CompanionEntity companion, int radius) {
        if (!(companion.level() instanceof ServerLevel level) || companion.ownerUuid() == null || radius < 0) return List.of();
        long now = level.getGameTime();
        double radiusSquared = (double) radius * radius;
        return visibleNow.getOrDefault(companion.ownerUuid(), List.of()).stream()
                .filter(observation -> observation.observedTick() == now)
                .filter(observation -> observation.location().dimension().equals(level.dimension().identifier().toString()))
                .filter(observation -> distanceSquared(companion, observation) <= radiusSquared)
                .sorted(Comparator.comparingDouble(observation -> distanceSquared(companion, observation)))
                .toList();
    }

    /** Exact remembered positions matching a block id or tag, with no world or chunk reads. */
    public List<BlockPos> remembered(CompanionEntity companion, BlockMatcher matcher, int radius, int limit) {
        if (matcher == null || companion.ownerUuid() == null || limit < 1 || radius < 0) return List.of();
        ServerLevel level = (ServerLevel) companion.level();
        ResourceKnowledge book = knowledge.book(companion.ownerUuid());
        double radiusSquared = (double) radius * radius;
        return book.snapshot().records().stream()
                .filter(record -> record.dimension().equals(level.dimension().identifier().toString()))
                .filter(record -> matches(matcher, record.blockId()))
                .map(record -> new BlockPos(record.x(), record.y(), record.z()))
                .filter(pos -> companion.position().distanceToSqr(Vec3.atCenterOf(pos)) <= radiusSquared)
                .distinct()
                .sorted(Comparator.comparingDouble(pos -> companion.position().distanceToSqr(Vec3.atCenterOf(pos))))
                .limit(limit)
                .toList();
    }

    public List<BlockPos> knownOrVisible(CompanionEntity companion, BlockMatcher matcher, int radius, int limit) {
        if (limit < 1) return List.of();
        java.util.LinkedHashSet<BlockPos> positions = new java.util.LinkedHashSet<>();
        positions.addAll(visible(companion, matcher, radius, limit));
        if (positions.size() < limit) positions.addAll(remembered(companion, matcher, radius, limit - positions.size()));
        return positions.stream()
                .sorted(Comparator.comparingDouble(pos -> companion.position().distanceToSqr(Vec3.atCenterOf(pos))))
                .limit(limit)
                .toList();
    }

    public boolean currentlyVisible(CompanionEntity companion, BlockPos pos) {
        if (!(companion.level() instanceof ServerLevel level) || companion.ownerUuid() == null) return false;
        String dimension = level.dimension().identifier().toString();
        long tick = level.getGameTime();
        return visibleNow.getOrDefault(companion.ownerUuid(), List.of()).stream().anyMatch(observation ->
                observation.observedTick() == tick && observation.location().dimension().equals(dimension)
                        && observation.location().pos().x() == pos.getX()
                        && observation.location().pos().y() == pos.getY()
                        && observation.location().pos().z() == pos.getZ());
    }

    public java.util.Optional<String> rememberedBlockId(CompanionEntity companion, BlockPos pos) {
        if (companion.ownerUuid() == null || !(companion.level() instanceof ServerLevel level)) return java.util.Optional.empty();
        return knowledge.book(companion.ownerUuid()).snapshot().records().stream()
                .filter(record -> record.dimension().equals(level.dimension().identifier().toString()))
                .filter(record -> record.x() == pos.getX() && record.y() == pos.getY() && record.z() == pos.getZ())
                .map(dev.friendai.knowledge.KnowledgeFile.MemberRecord::blockId)
                .findFirst();
    }

    private static boolean matches(BlockMatcher matcher, String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return false;
        return matcher.test(BuiltInRegistries.BLOCK.getValue(id).defaultBlockState());
    }

    private static double distanceSquared(CompanionEntity companion, ResourceObservation observation) {
        var pos = observation.location().pos();
        return companion.position().distanceToSqr(Vec3.atCenterOf(new BlockPos(pos.x(), pos.y(), pos.z())));
    }

    /** Queries the owner's in-memory evidence only; it does not load or inspect world chunks. */
    public List<ResourceQuery.Result> known(CompanionEntity companion, ResourceQuery.Request request) {
        if (companion.ownerUuid() == null) return List.of();
        return ResourceQuery.find(knowledge.book(companion.ownerUuid()), request);
    }

    /** Converts owner-scoped historical sites into planner candidates without reading world chunks. */
    public List<ResourceCandidate> plannerCandidates(CompanionEntity companion, int limitPerBlock) {
        if (companion.ownerUuid() == null || !(companion.level() instanceof ServerLevel level)) return List.of();
        ResourceKnowledge book = knowledge.book(companion.ownerUuid());
        String dimension = level.dimension().identifier().toString();
        BlockPos origin = companion.blockPosition();
        Set<String> blockIds = book.snapshot().records().stream()
                .filter(record -> record.dimension().equals(dimension))
                .map(dev.friendai.knowledge.KnowledgeFile.MemberRecord::blockId)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, ResourceCandidate> result = new java.util.LinkedHashMap<>();
        for (String blockId : blockIds) {
            ResourceQuery.Request request = new ResourceQuery.Request(Set.of(blockId), dimension,
                    new Pos(origin.getX(), origin.getY(), origin.getZ()), 30_000_000, limitPerBlock, true);
            for (ResourceQuery.Result found : ResourceQuery.find(book, request, level.getGameTime())) {
                ResourceSite site = found.site();
                Pos approach = site.approach();
                double dx = approach.x() - companion.getX();
                double dz = approach.z() - companion.getZ();
                double dy = Math.abs(approach.y() - companion.getY());
                boolean fresh = site.availability() == ResourceSite.Availability.OBSERVED;
                double cost = 4.6 * Math.hypot(dx, dz) + 10 * dy + (fresh ? 0 : 200);
                boolean visible = site.members().stream().anyMatch(pos -> visibleNow.getOrDefault(companion.ownerUuid(), List.of()).stream()
                        .anyMatch(observation -> observation.observedTick() == level.getGameTime()
                                && observation.location().pos().equals(pos)));
                ResourceCandidate candidate = new ResourceCandidate(site.id(), blockId, site.dimension(),
                        site.representative(), approach, found.lastObservedBlocks(), found.ageTicks(), visible,
                        site.approximate(), cost);
                result.putIfAbsent(site.id() + ":" + blockId, candidate);
            }
        }
        return result.values().stream().sorted(Comparator.comparingLong(ResourceCandidate::siteId)
                .thenComparing(ResourceCandidate::blockId)).toList();
    }

    /** Queues fresh bounded sight checks for remembered exact members of the requested site. */
    public Verification verify(CompanionEntity companion, long siteId) {
        if (companion.ownerUuid() == null || !(companion.level() instanceof ServerLevel level)) return Verification.UNLOADED;
        ResourceKnowledge book = knowledge.book(companion.ownerUuid());
        ResourceSite site = book.site(siteId, level.getGameTime()).orElse(null);
        if (site == null) return Verification.ABSENT;
        if (site.members().isEmpty()) return Verification.PENDING;
        ResourceObserver observer = observations.observer(companion);
        site.members().forEach(pos -> observer.inspect(new BlockPos(pos.x(), pos.y(), pos.z())));
        return Verification.PENDING;
    }

    public java.util.Optional<ResourceSite> site(CompanionEntity companion, long siteId) {
        if (companion.ownerUuid() == null || !(companion.level() instanceof ServerLevel level)) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(knowledge.book(companion.ownerUuid()).site(siteId, level.getGameTime()).orElse(null));
    }

    public boolean siteVisible(CompanionEntity companion, long siteId) {
        ResourceSite site = site(companion, siteId).orElse(null);
        if (site == null) return false;
        String dimension = ((ServerLevel) companion.level()).dimension().identifier().toString();
        long tick = ((ServerLevel) companion.level()).getGameTime();
        return visibleNow.getOrDefault(companion.ownerUuid(), List.of()).stream().anyMatch(observation ->
                observation.observedTick() == tick && observation.location().dimension().equals(dimension)
                        && site.members().contains(observation.location().pos())
                        && site.family().equals(observation.blockId()));
    }

    /** Applies evidence of this companion's confirmed successful break and schedules nearby face checks. */
    public void ownBreak(CompanionEntity companion, BlockPos pos) {
        if (companion.ownerUuid() == null || !(companion.level() instanceof ServerLevel level)) return;
        ResourceObservation.Location location = new ResourceObservation.Location(
                level.dimension().identifier().toString(), new Pos(pos.getX(), pos.getY(), pos.getZ()));
        knowledge.book(companion.ownerUuid()).extracted(location, level.getGameTime());
        observations.observer(companion).changed(pos);
    }
}
