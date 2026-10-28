package dev.friendai.knowledge;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned, registry-agnostic persistence DTO for one owner's spatial knowledge. */
public final class KnowledgeFile {
    public static final int CURRENT_VERSION = 2;

    private int version = CURRENT_VERSION;
    private long logicalTick;
    private long nextSiteId = 1;
    private List<MemberRecord> records = new ArrayList<>();
    private List<SiteRecord> sites = new ArrayList<>();
    private List<SummaryRecord> summaries = new ArrayList<>();
    private Map<Long, Long> aliases = new LinkedHashMap<>();
    private List<JsonObject> coverage = new ArrayList<>();
    private List<JsonObject> trails = new ArrayList<>();
    private List<JsonObject> searchLedger = new ArrayList<>();
    private JsonObject searchStats = new JsonObject();
    private List<ContainerRecord> containers = new ArrayList<>();

    public KnowledgeFile() {}

    KnowledgeFile(long logicalTick, long nextSiteId, List<MemberRecord> records, List<SiteRecord> sites,
                  List<SummaryRecord> summaries, Map<Long, Long> aliases) {
        this(logicalTick, nextSiteId, records, sites, summaries, aliases, List.of());
    }

    KnowledgeFile(long logicalTick, long nextSiteId, List<MemberRecord> records, List<SiteRecord> sites,
                  List<SummaryRecord> summaries, Map<Long, Long> aliases, List<ContainerRecord> containers) {
        this(logicalTick, nextSiteId, records, sites, summaries, aliases, containers, List.of());
    }

    KnowledgeFile(long logicalTick, long nextSiteId, List<MemberRecord> records, List<SiteRecord> sites,
                  List<SummaryRecord> summaries, Map<Long, Long> aliases, List<ContainerRecord> containers,
                  List<JsonObject> coverage) {
        this(logicalTick, nextSiteId, records, sites, summaries, aliases, containers, coverage, List.of(), List.of());
    }

    KnowledgeFile(long logicalTick, long nextSiteId, List<MemberRecord> records, List<SiteRecord> sites,
                  List<SummaryRecord> summaries, Map<Long, Long> aliases, List<ContainerRecord> containers,
                  List<JsonObject> coverage, List<JsonObject> trails, List<JsonObject> searchLedger) {
        this(logicalTick, nextSiteId, records, sites, summaries, aliases, containers, coverage, trails, searchLedger,
                new JsonObject());
    }

    KnowledgeFile(long logicalTick, long nextSiteId, List<MemberRecord> records, List<SiteRecord> sites,
                  List<SummaryRecord> summaries, Map<Long, Long> aliases, List<ContainerRecord> containers,
                  List<JsonObject> coverage, List<JsonObject> trails, List<JsonObject> searchLedger,
                  JsonObject searchStats) {
        this.logicalTick = logicalTick;
        this.nextSiteId = nextSiteId;
        this.records = new ArrayList<>(records);
        this.sites = new ArrayList<>(sites);
        this.summaries = new ArrayList<>(summaries);
        this.aliases = new LinkedHashMap<>(aliases);
        this.containers = new ArrayList<>(containers);
        this.coverage = new ArrayList<>(coverage);
        this.trails = new ArrayList<>(trails);
        this.searchLedger = new ArrayList<>(searchLedger);
        this.searchStats = searchStats == null ? new JsonObject() : searchStats;
    }

    /** Upgrades an older file in place; a version this code does not know still fails validation. */
    KnowledgeFile migrate() {
        if (version == 1) {
            version = CURRENT_VERSION;
            if (searchStats == null) searchStats = new JsonObject();
        }
        return this;
    }

    public int version() { return version; }
    public long logicalTick() { return logicalTick; }
    public long nextSiteId() { return nextSiteId; }
    public List<MemberRecord> records() { return List.copyOf(records); }
    public List<SiteRecord> sites() { return List.copyOf(sites); }
    public List<SummaryRecord> summaries() { return List.copyOf(summaries); }
    public Map<Long, Long> aliases() { return Map.copyOf(aliases); }
    public List<JsonObject> coverage() { return List.copyOf(coverage); }
    public List<JsonObject> trails() { return List.copyOf(trails); }
    public List<JsonObject> searchLedger() { return List.copyOf(searchLedger); }
    public JsonObject searchStats() { return searchStats == null ? new JsonObject() : searchStats; }
    public List<ContainerRecord> containers() { return containers == null ? List.of() : List.copyOf(containers); }

    void validate(KnowledgeLimits limits) {
        if (version != CURRENT_VERSION) throw new IllegalArgumentException("unsupported knowledge version");
        if (logicalTick < 0 || nextSiteId < 1) throw new IllegalArgumentException("invalid knowledge clock or id");
        if (records == null || sites == null || summaries == null || aliases == null || coverage == null || trails == null || searchLedger == null || containers == null || searchStats == null) {
            throw new IllegalArgumentException("missing knowledge collections");
        }
        if (records.size() > limits.memberObservations() || records.size() > 16_384) {
            throw new IllegalArgumentException("too many member records");
        }
        if (containers.size() > 256) throw new IllegalArgumentException("too many container records");
        int itemEntries = 0;
        java.util.Set<String> containerLocations = new java.util.HashSet<>();
        for (ContainerRecord record : containers) {
            if (record == null || blank(record.dimension) || record.items == null || record.observedTick < 0
                    || record.observedTick > logicalTick) throw new IllegalArgumentException("invalid container record");
            validatePosition(record.x, record.y, record.z);
            String key = record.dimension + '\u0000' + record.x + ',' + record.y + ',' + record.z;
            if (!containerLocations.add(key)) throw new IllegalArgumentException("duplicate container location");
            for (Map.Entry<String, Integer> item : record.items.entrySet()) {
                if (blank(item.getKey()) || item.getValue() == null || item.getValue() <= 0 || item.getValue() > 1_000_000) {
                    throw new IllegalArgumentException("invalid container item record");
                }
                itemEntries++;
            }
        }
        if (records.size() + itemEntries > 16_384) throw new IllegalArgumentException("too many exact records");
        for (JsonObject row : coverage) {
            if (row == null || !row.has("dimension") || !row.has("x") || !row.has("y") || !row.has("z")
                    || !row.has("family") || !row.has("strategy") || !row.has("outcome") || !row.has("tick")) {
                throw new IllegalArgumentException("invalid coverage record");
            }
            String dimension = row.get("dimension").getAsString();
            String family = row.get("family").getAsString();
            long tick = row.get("tick").getAsLong();
            if (blank(dimension) || blank(family) || tick < 0 || tick > logicalTick) throw new IllegalArgumentException("invalid coverage record");
            validatePosition(row.get("x").getAsInt(), row.get("y").getAsInt(), row.get("z").getAsInt());
            try {
                dev.friendai.knowledge.search.ResourceSearchProfile.Strategy.valueOf(row.get("strategy").getAsString());
                dev.friendai.knowledge.search.SearchCoverage.Outcome.valueOf(row.get("outcome").getAsString());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("invalid coverage strategy or outcome", ex);
            }
        }
        for (JsonObject row : searchLedger) {
            String[] fields = {"owner", "dimension", "goal", "id", "x", "y", "z", "radius", "travel",
                    "limit_ticks", "excavation", "phase", "started", "last", "spent_travel", "spent_ticks", "excavated", "blocked"};
            if (row == null || java.util.Arrays.stream(fields).anyMatch(field -> !row.has(field)))
                throw new IllegalArgumentException("invalid search ledger entry");
            try {
                java.util.UUID.fromString(row.get("owner").getAsString());
                java.util.UUID.fromString(row.get("id").getAsString());
                if (blank(row.get("dimension").getAsString()) || blank(row.get("goal").getAsString()))
                    throw new IllegalArgumentException("blank search ledger key");
                validatePosition(row.get("x").getAsInt(), row.get("y").getAsInt(), row.get("z").getAsInt());
                new dev.friendai.knowledge.search.SearchSession.Limits(row.get("radius").getAsInt(),
                        row.get("travel").getAsDouble(), row.get("limit_ticks").getAsLong(), row.get("excavation").getAsInt());
                dev.friendai.knowledge.search.SearchSession.Phase.valueOf(row.get("phase").getAsString());
                long started = row.get("started").getAsLong(), last = row.get("last").getAsLong();
                if (started < 0 || last < started || last > logicalTick || row.get("spent_ticks").getAsLong() < 0
                        || row.get("spent_travel").getAsDouble() < 0 || row.get("excavated").getAsInt() < 0
                        || row.get("blocked").getAsInt() < 0) throw new IllegalArgumentException("invalid search ledger counters");
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("invalid search ledger entry", ex);
            }
        }
        dev.friendai.knowledge.travel.TrailGraph.fromJson(trails);
        if (sites.size() + summaries.size() > limits.sites() || sites.size() + summaries.size() > 2_048) {
            throw new IllegalArgumentException("too many resource sites");
        }
        if (aliases.size() > limits.aliases() || coverage.size() > limits.coverageEntries()
                || trails.size() > limits.trailNodes() || searchLedger.size() > 2_048) {
            throw new IllegalArgumentException("knowledge collection exceeds configured bounds");
        }
        java.util.Set<String> locations = new java.util.HashSet<>();
        java.util.Set<Long> siteIds = new java.util.HashSet<>();
        java.util.Set<Long> sitesWithMembers = new java.util.HashSet<>();
        Map<Long, SiteRecord> siteById = new java.util.HashMap<>();
        long maximumId = 0;
        for (SiteRecord site : sites) {
            if (site == null || site.id < 1 || blank(site.family) || blank(site.dimension)
                    || site.firstSeen < 0 || site.lastSeen < site.firstSeen || site.lastSeen > logicalTick) {
                throw new IllegalArgumentException("invalid resource site");
            }
            validatePosition(site.x, site.y, site.z);
            validatePosition(site.approachX, site.approachY, site.approachZ);
            if (!siteIds.add(site.id)) throw new IllegalArgumentException("duplicate resource site id");
            siteById.put(site.id, site);
            maximumId = Math.max(maximumId, site.id);
        }
        for (MemberRecord record : records) {
            if (record == null || blank(record.blockId) || blank(record.family) || blank(record.dimension)
                    || record.siteId < 1 || record.firstSeen < 0 || record.lastSeen < record.firstSeen
                    || record.lastSeen > logicalTick) throw new IllegalArgumentException("invalid resource record");
            validatePosition(record.x, record.y, record.z);
            validatePosition(record.viewX, record.viewY, record.viewZ);
            String location = record.dimension + '\u0000' + record.x + ',' + record.y + ',' + record.z;
            if (!locations.add(location)) throw new IllegalArgumentException("duplicate resource location");
            if (!siteIds.contains(record.siteId)) throw new IllegalArgumentException("record references missing site");
            SiteRecord site = siteById.get(record.siteId);
            if (!site.family.equals(record.family) || !site.dimension.equals(record.dimension)
                    || record.firstSeen < site.firstSeen || record.lastSeen > site.lastSeen) {
                throw new IllegalArgumentException("resource record does not match its site");
            }
            sitesWithMembers.add(record.siteId);
        }
        for (SummaryRecord summary : summaries) {
            if (summary == null || summary.id < 1 || blank(summary.family) || blank(summary.dimension)
                    || summary.lastCount < 1 || summary.firstSeen < 0 || summary.lastSeen < summary.firstSeen
                    || summary.lastSeen > logicalTick || summary.blockIds == null || summary.blockIds.isEmpty()) {
                throw new IllegalArgumentException("invalid resource summary");
            }
            validatePosition(summary.x, summary.y, summary.z);
            validatePosition(summary.approachX, summary.approachY, summary.approachZ);
            if (!siteIds.add(summary.id)) throw new IllegalArgumentException("duplicate resource site id");
            maximumId = Math.max(maximumId, summary.id);
            for (String blockId : summary.blockIds) if (blank(blockId)) throw new IllegalArgumentException("invalid summary block id");
        }
        for (SiteRecord site : sites) {
            if (site.depleted == sitesWithMembers.contains(site.id)) {
                throw new IllegalArgumentException("site availability does not match its members");
            }
        }
        if (nextSiteId <= maximumId) throw new IllegalArgumentException("next site id is not monotonic");
        for (Map.Entry<Long, Long> alias : aliases.entrySet()) {
            if (alias.getKey() == null || alias.getValue() == null || alias.getKey() < 1 || alias.getValue() < 1
                    || alias.getKey().equals(alias.getValue()) || siteIds.contains(alias.getKey())) {
                throw new IllegalArgumentException("invalid site alias");
            }
        }
        for (long alias : aliases.keySet()) {
            long target = alias;
            java.util.Set<Long> visited = new java.util.HashSet<>();
            while (aliases.containsKey(target)) {
                if (!visited.add(target)) throw new IllegalArgumentException("cyclic site alias");
                target = aliases.get(target);
            }
            if (!siteIds.contains(target)) throw new IllegalArgumentException("alias target is missing");
        }
    }

    private static int distinctSiteIds(List<MemberRecord> records) {
        if (records == null) return 0;
        return (int) records.stream().filter(java.util.Objects::nonNull).map(record -> record.siteId).distinct().count();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public static final class ContainerRecord {
        private String dimension;
        private int x, y, z;
        private Map<String, Integer> items = new LinkedHashMap<>();
        private long observedTick;
        public ContainerRecord() {}
        ContainerRecord(String dimension, int x, int y, int z, Map<String, Integer> items, long observedTick) {
            this.dimension = dimension; this.x = x; this.y = y; this.z = z;
            this.items = new LinkedHashMap<>(items); this.observedTick = observedTick;
        }
        public String dimension() { return dimension; }
        public int x() { return x; }
        public int y() { return y; }
        public int z() { return z; }
        public Map<String, Integer> items() { return Map.copyOf(items); }
        public long observedTick() { return observedTick; }
    }

    private static void validatePosition(int x, int y, int z) {
        if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000 || Math.abs((long) y) > 2_048) {
            throw new IllegalArgumentException("resource position is outside bounds");
        }
    }

    public static final class SiteRecord {
        private long id;
        private String family, dimension;
        private int x, y, z, approachX, approachY, approachZ;
        private long firstSeen, lastSeen;
        private boolean depleted;

        public SiteRecord() {}
        SiteRecord(long id, String family, String dimension, int x, int y, int z, int approachX, int approachY,
                   int approachZ, long firstSeen, long lastSeen, boolean depleted) {
            this.id = id; this.family = family; this.dimension = dimension;
            this.x = x; this.y = y; this.z = z; this.approachX = approachX; this.approachY = approachY;
            this.approachZ = approachZ; this.firstSeen = firstSeen; this.lastSeen = lastSeen; this.depleted = depleted;
        }
        public long id() { return id; }
        public String family() { return family; }
        public String dimension() { return dimension; }
        public int x() { return x; }
        public int y() { return y; }
        public int z() { return z; }
        public int approachX() { return approachX; }
        public int approachY() { return approachY; }
        public int approachZ() { return approachZ; }
        public long firstSeen() { return firstSeen; }
        public long lastSeen() { return lastSeen; }
        public boolean depleted() { return depleted; }
    }

    public static final class MemberRecord {
        private String blockId;
        private String family;
        private String dimension;
        private int x, y, z;
        private int viewX, viewY, viewZ;
        private long firstSeen, lastSeen, siteId;

        public MemberRecord() {}
        MemberRecord(String blockId, String family, String dimension, int x, int y, int z,
                     int viewX, int viewY, int viewZ, long firstSeen, long lastSeen, long siteId) {
            this.blockId = blockId; this.family = family; this.dimension = dimension;
            this.x = x; this.y = y; this.z = z; this.viewX = viewX; this.viewY = viewY; this.viewZ = viewZ;
            this.firstSeen = firstSeen; this.lastSeen = lastSeen; this.siteId = siteId;
        }
        public String blockId() { return blockId; }
        public String family() { return family; }
        public String dimension() { return dimension; }
        public int x() { return x; }
        public int y() { return y; }
        public int z() { return z; }
        public int viewX() { return viewX; }
        public int viewY() { return viewY; }
        public int viewZ() { return viewZ; }
        public long firstSeen() { return firstSeen; }
        public long lastSeen() { return lastSeen; }
        public long siteId() { return siteId; }
    }

    public static final class SummaryRecord {
        private long id;
        private String family, dimension;
        private int lastCount, x, y, z, approachX, approachY, approachZ;
        private List<String> blockIds = new ArrayList<>();
        private long firstSeen, lastSeen;

        public SummaryRecord() {}
        SummaryRecord(long id, String family, String dimension, int lastCount, List<String> blockIds,
                      int x, int y, int z, int approachX, int approachY, int approachZ, long firstSeen, long lastSeen) {
            this.id = id; this.family = family; this.dimension = dimension; this.lastCount = lastCount;
            this.blockIds = new ArrayList<>(blockIds); this.x = x; this.y = y; this.z = z;
            this.approachX = approachX; this.approachY = approachY; this.approachZ = approachZ;
            this.firstSeen = firstSeen; this.lastSeen = lastSeen;
        }
        public long id() { return id; }
        public String family() { return family; }
        public String dimension() { return dimension; }
        public int lastCount() { return lastCount; }
        public List<String> blockIds() { return List.copyOf(blockIds); }
        public int x() { return x; }
        public int y() { return y; }
        public int z() { return z; }
        public int approachX() { return approachX; }
        public int approachY() { return approachY; }
        public int approachZ() { return approachZ; }
        public long firstSeen() { return firstSeen; }
        public long lastSeen() { return lastSeen; }
    }
}
