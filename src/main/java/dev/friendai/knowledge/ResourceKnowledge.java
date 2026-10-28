package dev.friendai.knowledge;

import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.knowledge.search.SearchCoverage;
import dev.friendai.knowledge.search.SearchLedger;
import dev.friendai.knowledge.travel.TrailGraph;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ResourceKnowledge {
    private static final long STALE_AFTER_TICKS = 24_000;
    private static final int[][] NEIGHBORS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private final KnowledgeLimits limits;
    private final Map<ResourceObservation.Location, Member> members = new HashMap<>();
    private final Map<ResourceObservation.Location, Long> siteAt = new HashMap<>();
    private final Map<Long, MutableSite> sites = new LinkedHashMap<>();
    private final Map<Long, Summary> summaries = new LinkedHashMap<>();
    private final Map<Long, Long> aliases = new LinkedHashMap<>();
    private final Map<FamilyKey, Set<Long>> byFamily = new HashMap<>();
    private final Map<String, Set<Long>> byBlockId = new HashMap<>();
    private final Set<Long> pinned = new HashSet<>();
    private final Map<ResourceObservation.Location, ContainerObservation> containers = new LinkedHashMap<>();
    private SearchCoverage searchCoverage = new SearchCoverage();
    private SearchLedger searchLedger = new SearchLedger();
    private TrailGraph trails = new TrailGraph();
    private long nextId = 1;
    private long logicalTick;
    private long revision;

    public ResourceKnowledge(KnowledgeLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        searchLedger.onChange(() -> revision++);
    }

    public record ContainerObservation(
            ResourceObservation.Location location, Map<String, Integer> items, long observedTick) {
        public ContainerObservation {
            Objects.requireNonNull(location, "location");
            items = Map.copyOf(items);
            if (observedTick < 0) throw new IllegalArgumentException("observedTick must not be negative");
            if (items.size() > 16_384
                    || items.entrySet().stream()
                            .anyMatch(entry -> entry.getKey() == null
                                    || entry.getKey().isBlank()
                                    || entry.getValue() == null
                                    || entry.getValue() < 1
                                    || entry.getValue() > 1_000_000))
                throw new IllegalArgumentException("invalid container contents");
        }
    }

    /** Stores a bounded snapshot taken after an authorized container interaction. */
    public boolean recordContainer(ContainerObservation observation) {
        Objects.requireNonNull(observation, "observation");
        advance(observation.observedTick());
        boolean replacing = containers.containsKey(observation.location());
        int entries = containers.values().stream()
                .mapToInt(value -> value.items().size())
                .sum();
        if (!replacing && containers.size() >= 256) return false;
        if (entries
                        - (replacing
                                ? containers.get(observation.location()).items().size()
                                : 0)
                        + observation.items().size()
                        + members.size()
                > 16_384) return false;
        containers.put(observation.location(), observation);
        revision++;
        return true;
    }

    public void invalidateContainer(ResourceObservation.Location location) {
        if (containers.remove(location) != null) revision++;
    }

    /** Records direct evidence only; returns -1 when configured caps defer this observation. */
    public long observe(ResourceObservation observation, String family) {
        Objects.requireNonNull(observation, "observation");
        if (family == null || family.isBlank()) throw new IllegalArgumentException("family must not be blank");
        long tick = advance(observation.observedTick());
        ResourceObservation.Location location = observation.location();
        Member existing = members.get(location);
        if (existing != null) {
            long resolved = resolve(existing.siteId);
            MutableSite oldSite = sites.get(resolved);
            if (oldSite != null && oldSite.family.equals(family) && existing.blockId.equals(observation.blockId())) {
                existing.lastSeen = Math.max(existing.lastSeen, tick);
                existing.viewpoint = observation.viewpoint();
                oldSite.lastSeen = Math.max(oldSite.lastSeen, tick);
                oldSite.approach = observation.viewpoint();
                revision++;
                return resolved;
            }
            removeMember(location);
        }

        Set<Long> adjacent = adjacentSites(location, family);
        if (members.size() >= limits.memberObservations() && !compactOne(adjacent)) return -1;
        if (adjacent.isEmpty() && siteCount() >= limits.sites() && !makeSiteRoom()) return -1;

        long id = adjacent.stream().min(Long::compareTo).orElseGet(() -> nextId++);
        MutableSite target = sites.computeIfAbsent(
                id,
                ignored -> new MutableSite(
                        id, family, location.dimension(), location.pos(), observation.viewpoint(), tick));
        for (long neighbor : adjacent) {
            if (neighbor != id) mergeInto(id, neighbor);
        }
        Member member = new Member(observation.blockId(), family, tick, observation.viewpoint(), id);
        members.put(location, member);
        siteAt.put(location, id);
        target.positions.add(location.pos());
        target.firstSeen = Math.min(target.firstSeen, tick);
        target.lastSeen = Math.max(target.lastSeen, tick);
        target.approach = observation.viewpoint();
        target.depleted = false;
        target.representative = representative(target.positions);
        target.blockIds.add(observation.blockId());
        target.blockCounts.merge(observation.blockId(), 1, Integer::sum);
        indexMember(id, family, observation.blockId());
        revision++;
        return id;
    }

    public void confirmAbsent(ResourceObservation.Location location, long tick) {
        advance(tick);
        if (removeMember(location)) revision++;
    }

    public void extracted(ResourceObservation.Location location, long tick) {
        confirmAbsent(location, tick);
    }

    public Optional<ResourceSite> site(long id, long now) {
        long resolved = resolve(id);
        MutableSite detailed = sites.get(resolved);
        long tick = Math.max(logicalTick, now);
        if (detailed != null) return Optional.of(detailed.snapshot(tick));
        Summary summary = summaries.get(resolved);
        return summary == null ? Optional.empty() : Optional.of(summary.snapshot(tick));
    }

    /** Historical distinct block count; approximate summaries are explicitly marked by their site snapshot. */
    public int historicalBlockCount(long id) {
        long resolved = resolve(id);
        MutableSite detailed = sites.get(resolved);
        if (detailed != null) return detailed.positions.size();
        Summary summary = summaries.get(resolved);
        return summary == null ? 0 : summary.lastCount;
    }

    public long revision() {
        return revision;
    }

    /** Previously inspected container contents, copied so callers cannot mutate the knowledge book. */
    public List<ContainerObservation> containerObservations() {
        return List.copyOf(containers.values());
    }

    public SearchCoverage searchCoverage() {
        return searchCoverage;
    }

    public SearchLedger searchLedger() {
        return searchLedger;
    }

    public TrailGraph trails() {
        return trails;
    }

    public void recordCoverage(
            String dimension,
            Pos viewpoint,
            String family,
            dev.friendai.knowledge.search.ResourceSearchProfile.Strategy strategy,
            SearchCoverage.Outcome outcome,
            long tick) {
        searchCoverage.record(dimension, viewpoint, family, strategy, outcome, tick);
        advance(tick);
        revision++;
    }

    public void pin(long siteId) {
        long resolved = resolve(siteId);
        if (sites.containsKey(resolved) || summaries.containsKey(resolved)) pinned.add(resolved);
    }

    public void unpin(long siteId) {
        pinned.remove(resolve(siteId));
    }

    long logicalTick() {
        return logicalTick;
    }

    public KnowledgeFile snapshot() {
        List<KnowledgeFile.MemberRecord> records = new ArrayList<>();
        for (Map.Entry<ResourceObservation.Location, Member> entry : members.entrySet()) {
            Member member = entry.getValue();
            ResourceObservation.Location location = entry.getKey();
            records.add(new KnowledgeFile.MemberRecord(
                    member.blockId,
                    member.family,
                    location.dimension(),
                    location.pos().x(),
                    location.pos().y(),
                    location.pos().z(),
                    member.viewpoint.x(),
                    member.viewpoint.y(),
                    member.viewpoint.z(),
                    member.firstSeen,
                    member.lastSeen,
                    resolve(member.siteId)));
        }
        records.sort(Comparator.comparingLong(KnowledgeFile.MemberRecord::siteId)
                .thenComparing(KnowledgeFile.MemberRecord::dimension)
                .thenComparingInt(KnowledgeFile.MemberRecord::x)
                .thenComparingInt(KnowledgeFile.MemberRecord::y)
                .thenComparingInt(KnowledgeFile.MemberRecord::z));
        List<KnowledgeFile.SiteRecord> siteRecords = sites.values().stream()
                .sorted(Comparator.comparingLong(site -> site.id))
                .map(site -> new KnowledgeFile.SiteRecord(
                        site.id,
                        site.family,
                        site.dimension,
                        site.representative.x(),
                        site.representative.y(),
                        site.representative.z(),
                        site.approach.x(),
                        site.approach.y(),
                        site.approach.z(),
                        site.firstSeen,
                        site.lastSeen,
                        site.depleted))
                .toList();
        List<KnowledgeFile.SummaryRecord> summaryRecords = summaries.values().stream()
                .sorted(Comparator.comparingLong(Summary::id))
                .map(summary -> new KnowledgeFile.SummaryRecord(
                        summary.id,
                        summary.family,
                        summary.dimension,
                        summary.lastCount,
                        summary.blockIds.stream().sorted().toList(),
                        summary.representative.x(),
                        summary.representative.y(),
                        summary.representative.z(),
                        summary.approach.x(),
                        summary.approach.y(),
                        summary.approach.z(),
                        summary.firstSeen,
                        summary.lastSeen))
                .toList();
        List<KnowledgeFile.ContainerRecord> containerRecords = containers.values().stream()
                .map(observation -> new KnowledgeFile.ContainerRecord(
                        observation.location().dimension(),
                        observation.location().pos().x(),
                        observation.location().pos().y(),
                        observation.location().pos().z(),
                        observation.items(),
                        observation.observedTick()))
                .toList();
        return new KnowledgeFile(
                Math.max(logicalTick, searchLedger.logicalTick()),
                nextId,
                records,
                siteRecords,
                summaryRecords,
                aliases,
                containerRecords,
                searchCoverage.toJson(),
                trails.toJson(),
                searchLedger.toJson(),
                searchLedger.toStatsJson());
    }

    public static ResourceKnowledge restore(KnowledgeFile file, KnowledgeLimits limits) {
        Objects.requireNonNull(file, "file").validate(limits);
        ResourceKnowledge book = new ResourceKnowledge(limits);
        book.logicalTick = file.logicalTick();
        book.searchCoverage = SearchCoverage.fromJson(file.coverage());
        book.searchLedger.restore(file.searchLedger());
        book.searchLedger.restoreStats(file.searchStats());
        book.searchLedger.interruptActive();
        book.trails = TrailGraph.fromJson(file.trails());
        book.nextId = file.nextSiteId();
        for (KnowledgeFile.SiteRecord record : file.sites()) {
            MutableSite site = new MutableSite(
                    record.id(),
                    record.family(),
                    record.dimension(),
                    new Pos(record.x(), record.y(), record.z()),
                    new Pos(record.approachX(), record.approachY(), record.approachZ()),
                    record.firstSeen());
            site.lastSeen = record.lastSeen();
            site.depleted = record.depleted();
            book.sites.put(site.id, site);
        }
        for (KnowledgeFile.MemberRecord record : file.records()) {
            ResourceObservation.Location location =
                    new ResourceObservation.Location(record.dimension(), new Pos(record.x(), record.y(), record.z()));
            MutableSite site = book.sites.get(record.siteId());
            if (!site.family.equals(record.family()) || !site.dimension.equals(record.dimension())) {
                throw new IllegalArgumentException("site contains incompatible resource records");
            }
            site.positions.add(location.pos());
            site.blockIds.add(record.blockId());
            site.blockCounts.merge(record.blockId(), 1, Integer::sum);
            site.firstSeen = Math.min(site.firstSeen, record.firstSeen());
            site.lastSeen = Math.max(site.lastSeen, record.lastSeen());
            if (record.lastSeen() >= site.lastSeen)
                site.approach = new Pos(record.viewX(), record.viewY(), record.viewZ());
            Member member = new Member(
                    record.blockId(),
                    record.family(),
                    record.firstSeen(),
                    new Pos(record.viewX(), record.viewY(), record.viewZ()),
                    record.siteId());
            member.lastSeen = record.lastSeen();
            book.members.put(location, member);
            book.siteAt.put(location, record.siteId());
        }
        for (MutableSite site : book.sites.values()) {
            if (!site.positions.isEmpty()) {
                site.representative = representative(site.positions);
                book.byFamily
                        .computeIfAbsent(new FamilyKey(site.dimension, site.family), ignored -> new LinkedHashSet<>())
                        .add(site.id);
                for (String blockId : site.blockIds)
                    book.byBlockId
                            .computeIfAbsent(blockId, ignored -> new LinkedHashSet<>())
                            .add(site.id);
            }
        }
        for (KnowledgeFile.SummaryRecord record : file.summaries()) {
            Summary summary = new Summary(
                    record.id(),
                    record.family(),
                    record.dimension(),
                    record.lastCount(),
                    Set.copyOf(record.blockIds()),
                    new Pos(record.x(), record.y(), record.z()),
                    new Pos(record.approachX(), record.approachY(), record.approachZ()),
                    record.firstSeen(),
                    record.lastSeen());
            book.summaries.put(summary.id, summary);
            book.byFamily
                    .computeIfAbsent(new FamilyKey(summary.dimension, summary.family), ignored -> new LinkedHashSet<>())
                    .add(summary.id);
            for (String blockId : summary.blockIds)
                book.byBlockId
                        .computeIfAbsent(blockId, ignored -> new LinkedHashSet<>())
                        .add(summary.id);
        }
        book.aliases.putAll(file.aliases());
        for (KnowledgeFile.ContainerRecord record : file.containers()) {
            var location =
                    new ResourceObservation.Location(record.dimension(), new Pos(record.x(), record.y(), record.z()));
            book.containers.put(location, new ContainerObservation(location, record.items(), record.observedTick()));
        }
        return book;
    }

    private long advance(long tick) {
        logicalTick = Math.max(logicalTick, tick);
        return logicalTick;
    }

    private long resolve(long id) {
        long current = id;
        Set<Long> seen = null;
        while (aliases.containsKey(current)) {
            if (seen == null) seen = new HashSet<>();
            if (!seen.add(current)) break;
            current = aliases.get(current);
        }
        return current;
    }

    private Set<Long> adjacentSites(ResourceObservation.Location location, String family) {
        Set<Long> result = new LinkedHashSet<>();
        Pos pos = location.pos();
        for (int[] offset : NEIGHBORS) {
            Long id = siteAt.get(new ResourceObservation.Location(
                    location.dimension(), pos.offset(offset[0], offset[1], offset[2])));
            if (id != null) {
                long resolved = resolve(id);
                MutableSite site = sites.get(resolved);
                if (site != null && site.family.equals(family)) result.add(resolved);
            }
        }
        return result;
    }

    private void mergeInto(long retainedId, long removedId) {
        MutableSite retained = sites.get(retainedId);
        MutableSite removed = sites.remove(removedId);
        if (retained == null || removed == null) return;
        retained.positions.addAll(removed.positions);
        retained.blockIds.addAll(removed.blockIds);
        removed.blockCounts.forEach((blockId, count) -> retained.blockCounts.merge(blockId, count, Integer::sum));
        retained.firstSeen = Math.min(retained.firstSeen, removed.firstSeen);
        retained.lastSeen = Math.max(retained.lastSeen, removed.lastSeen);
        retained.representative = representative(retained.positions);
        if (removed.lastSeen >= retained.lastSeen) retained.approach = removed.approach;
        for (ResourceObservation.Location location : members.keySet()) {
            if (members.get(location).siteId == removedId) {
                members.get(location).siteId = retainedId;
                siteAt.put(location, retainedId);
            }
        }
        for (String blockId : removed.blockIds) {
            Set<Long> indexed = byBlockId.get(blockId);
            if (indexed != null) indexed.remove(removedId);
            byBlockId.computeIfAbsent(blockId, ignored -> new LinkedHashSet<>()).add(retainedId);
        }
        Set<Long> familySites = byFamily.get(new FamilyKey(removed.dimension, removed.family));
        if (familySites != null) familySites.remove(removedId);
        if (pinned.remove(removedId)) pinned.add(retainedId);
        aliases.put(removedId, retainedId);
        trimAliases();
        revision++;
    }

    private boolean removeMember(ResourceObservation.Location location) {
        Member member = members.remove(location);
        Long id = siteAt.remove(location);
        if (member == null || id == null) return false;
        long resolved = resolve(id);
        MutableSite site = sites.get(resolved);
        if (site == null) return false;
        site.positions.remove(location.pos());
        site.lastSeen = Math.max(site.lastSeen, logicalTick);
        int remaining = site.blockCounts.getOrDefault(member.blockId, 0) - 1;
        if (remaining <= 0) {
            site.blockCounts.remove(member.blockId);
            if (site.blockIds.remove(member.blockId)) {
                removeIndexEntry(byBlockId, member.blockId, resolved);
            }
        } else {
            site.blockCounts.put(member.blockId, remaining);
        }
        if (site.positions.isEmpty()) {
            site.depleted = true;
            unindexSite(resolved, site);
            site.blockIds.clear();
            site.blockCounts.clear();
        } else {
            site.representative = representative(site.positions);
        }
        return true;
    }

    private boolean compactOne(Set<Long> protectedSites) {
        MutableSite candidate = sites.values().stream()
                .filter(site -> !pinned.contains(site.id) && !protectedSites.contains(site.id))
                .min(Comparator.comparingLong((MutableSite site) -> site.lastSeen)
                        .thenComparingLong(site -> site.id))
                .orElse(null);
        if (candidate == null) return false;
        summaries.put(
                candidate.id,
                new Summary(
                        candidate.id,
                        candidate.family,
                        candidate.dimension,
                        candidate.positions.size(),
                        candidate.blockIds,
                        candidate.representative,
                        candidate.approach,
                        candidate.firstSeen,
                        candidate.lastSeen));
        sites.remove(candidate.id);
        for (ResourceObservation.Location location : List.copyOf(members.keySet())) {
            if (resolve(members.get(location).siteId) == candidate.id) {
                members.remove(location);
                siteAt.remove(location);
            }
        }
        revision++;
        return true;
    }

    private boolean makeSiteRoom() {
        while (siteCount() >= limits.sites()) {
            Long candidate = summaries.values().stream()
                    .filter(summary -> !pinned.contains(summary.id))
                    .min(Comparator.comparingLong((Summary summary) -> summary.lastSeen)
                            .thenComparingLong(summary -> summary.id))
                    .map(summary -> summary.id)
                    .orElse(null);
            if (candidate != null) {
                Summary removed = summaries.remove(candidate);
                dropAliasesTo(candidate);
                removeIndexEntry(byFamily, new FamilyKey(removed.dimension, removed.family), candidate);
                for (String blockId : removed.blockIds) {
                    removeIndexEntry(byBlockId, blockId, candidate);
                }
                pinned.remove(candidate);
                continue;
            }
            MutableSite detailed = sites.values().stream()
                    .filter(site -> !pinned.contains(site.id))
                    .min(Comparator.comparingLong((MutableSite site) -> site.lastSeen)
                            .thenComparingLong(site -> site.id))
                    .orElse(null);
            if (detailed == null) return false;
            compactOne(Set.of());
            Summary compacted = summaries.remove(detailed.id);
            if (compacted == null) return false;
            dropAliasesTo(compacted.id);
            removeIndexEntry(byFamily, new FamilyKey(compacted.dimension, compacted.family), compacted.id);
            for (String blockId : compacted.blockIds) {
                removeIndexEntry(byBlockId, blockId, compacted.id);
            }
        }
        return true;
    }

    private int siteCount() {
        return sites.size() + summaries.size();
    }

    private void indexMember(long id, String family, String blockId) {
        MutableSite site = sites.get(id);
        byFamily.computeIfAbsent(new FamilyKey(site.dimension, family), ignored -> new LinkedHashSet<>())
                .add(id);
        byBlockId.computeIfAbsent(blockId, ignored -> new LinkedHashSet<>()).add(id);
    }

    private void unindexSite(long id, MutableSite site) {
        Set<Long> ids = byFamily.get(new FamilyKey(site.dimension, site.family));
        if (ids != null) removeIndexEntry(byFamily, new FamilyKey(site.dimension, site.family), id);
        for (String blockId : site.blockIds) {
            removeIndexEntry(byBlockId, blockId, id);
        }
    }

    private void trimAliases() {
        while (aliases.size() > limits.aliases())
            aliases.remove(aliases.keySet().iterator().next());
    }

    private void dropAliasesTo(long id) {
        Set<Long> removed = new HashSet<>();
        for (Map.Entry<Long, Long> entry : aliases.entrySet()) {
            if (entry.getKey() == id || resolve(entry.getValue()) == id) removed.add(entry.getKey());
        }
        removed.forEach(aliases::remove);
    }

    private static <K> void removeIndexEntry(Map<K, Set<Long>> index, K key, long id) {
        Set<Long> ids = index.get(key);
        if (ids != null && ids.remove(id) && ids.isEmpty()) index.remove(key);
    }

    private static Pos representative(Set<Pos> positions) {
        return positions.stream()
                .min(Comparator.comparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z))
                .orElseThrow();
    }

    List<ResourceSite> querySites(Set<String> blockIds, String dimension) {
        return querySites(blockIds, dimension, logicalTick);
    }

    List<ResourceSite> querySites(Set<String> blockIds, String dimension, long now) {
        Set<Long> matching = new HashSet<>();
        for (String blockId : blockIds) matching.addAll(byBlockId.getOrDefault(blockId, Set.of()));
        List<ResourceSite> result = new ArrayList<>();
        for (long id : matching) {
            long resolved = resolve(id);
            MutableSite site = sites.get(resolved);
            if (site != null && site.dimension.equals(dimension)) result.add(site.snapshot(Math.max(logicalTick, now)));
            Summary summary = summaries.get(resolved);
            if (summary != null && summary.dimension.equals(dimension))
                result.add(summary.snapshot(Math.max(logicalTick, now)));
        }
        return result;
    }

    private static final class Member {
        final String blockId;
        final String family;
        final long firstSeen;
        long lastSeen;
        Pos viewpoint;
        long siteId;

        Member(String blockId, String family, long tick, Pos viewpoint, long siteId) {
            this.blockId = blockId;
            this.family = family;
            this.firstSeen = tick;
            this.lastSeen = tick;
            this.viewpoint = viewpoint;
            this.siteId = siteId;
        }
    }

    private static final class MutableSite {
        final long id;
        final String family;
        final String dimension;
        final Set<Pos> positions = new LinkedHashSet<>();
        final Set<String> blockIds = new LinkedHashSet<>();
        final Map<String, Integer> blockCounts = new HashMap<>();
        Pos representative;
        Pos approach;
        long firstSeen;
        long lastSeen;
        boolean depleted;

        MutableSite(long id, String family, String dimension, Pos representative, Pos approach, long tick) {
            this.id = id;
            this.family = family;
            this.dimension = dimension;
            this.representative = representative;
            this.approach = approach;
            this.firstSeen = tick;
            this.lastSeen = tick;
        }

        ResourceSite snapshot(long now) {
            ResourceSite.Availability availability = now - lastSeen >= STALE_AFTER_TICKS
                    ? ResourceSite.Availability.STALE
                    : ResourceSite.Availability.OBSERVED;
            return new ResourceSite(
                    id,
                    family,
                    dimension,
                    Set.copyOf(positions),
                    representative,
                    approach,
                    firstSeen,
                    lastSeen,
                    depleted ? ResourceSite.Availability.DEPLETED : availability,
                    ResourceSite.RouteStatus.UNKNOWN,
                    false);
        }
    }

    private record Summary(
            long id,
            String family,
            String dimension,
            int lastCount,
            Set<String> blockIds,
            Pos representative,
            Pos approach,
            long firstSeen,
            long lastSeen) {
        Summary {
            blockIds = Set.copyOf(blockIds);
        }

        ResourceSite snapshot(long now) {
            ResourceSite.Availability availability = now - lastSeen >= STALE_AFTER_TICKS
                    ? ResourceSite.Availability.STALE
                    : ResourceSite.Availability.OBSERVED;
            return new ResourceSite(
                    id,
                    family,
                    dimension,
                    Set.of(),
                    representative,
                    approach,
                    firstSeen,
                    lastSeen,
                    availability,
                    ResourceSite.RouteStatus.UNKNOWN,
                    true);
        }
    }

    private record FamilyKey(String dimension, String family) {}
}
