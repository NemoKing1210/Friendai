package dev.friendai.perception.resource;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.knowledge.KnowledgeStore;
import dev.friendai.knowledge.ResourceKnowledge;
import dev.friendai.knowledge.ResourceObservation;
import dev.friendai.knowledge.ResourceQuery;
import dev.friendai.knowledge.ResourceSite;
import dev.friendai.pathing.Pos;
import dev.friendai.task.mining.BlockMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
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
        if (!(companion.level() instanceof ServerLevel level) || companion.ownerUuid() == null
                || radius < 0 || limit < 1) return List.of();
        long now = level.getGameTime();
        List<ResourceObservation> current = visibleNow.getOrDefault(companion.ownerUuid(), List.of());
        double radiusSquared = (double) radius * radius;
        return current.stream()
                .filter(observation -> observation.observedTick() == now)
                .filter(observation -> observation.location().dimension().equals(level.dimension().identifier().toString()))
                .map(observation -> new BlockPos(observation.location().pos().x(), observation.location().pos().y(),
                        observation.location().pos().z()))
                .filter(pos -> companion.position().distanceToSqr(Vec3.atCenterOf(pos)) <= radiusSquared)
                .filter(level::hasChunkAt)
                .filter(pos -> matcher.test(level.getBlockState(pos)))
                .distinct()
                .sorted(Comparator.comparingDouble(pos -> companion.position().distanceToSqr(Vec3.atCenterOf(pos))))
                .limit(limit)
                .toList();
    }

    /** Queries the owner's in-memory evidence only; it does not load or inspect world chunks. */
    public List<ResourceQuery.Result> known(CompanionEntity companion, ResourceQuery.Request request) {
        if (companion.ownerUuid() == null) return List.of();
        return ResourceQuery.find(knowledge.book(companion.ownerUuid()), request);
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

    /** Applies evidence of this companion's confirmed successful break and schedules nearby face checks. */
    public void ownBreak(CompanionEntity companion, BlockPos pos) {
        if (companion.ownerUuid() == null || !(companion.level() instanceof ServerLevel level)) return;
        ResourceObservation.Location location = new ResourceObservation.Location(
                level.dimension().identifier().toString(), new Pos(pos.getX(), pos.getY(), pos.getZ()));
        knowledge.book(companion.ownerUuid()).extracted(location, level.getGameTime());
        observations.observer(companion).changed(pos);
    }
}
