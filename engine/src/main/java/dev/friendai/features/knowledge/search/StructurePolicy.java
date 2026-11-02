package dev.friendai.features.knowledge.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Where a catalogued structure may be found and which search shape it needs. Data only: a new structure is a new
 * entry, and an id without an entry is not searchable in any dimension.
 *
 * <p>{@link Strategy#VERIFIED} means a marker sighting is proof of the structure. {@link Strategy#INDIRECT} means a
 * clue (an eye of ender, a map) can guide exploration but is never a found position; the search still has to see a
 * marker before it reports a structure. Both strategies are bounds on evidence, not a promise that every seed has one.
 */
public final class StructurePolicy {
    public enum Strategy {
        /** Markers on the surface: village, outpost, mansion, temples. */
        SURFACE,
        /** Markers under ground: stronghold, ancient city, trial chamber, mineshaft. */
        UNDERGROUND,
        /** Markers in the Nether: fortress, bastion, ruined portal. */
        NETHER,
        /** Markers under water: ocean monument. */
        OCEAN,
        /** A clue may guide the search but only a marker proves the find. */
        INDIRECT
    }

    public record Entry(Set<String> dimensions, Strategy strategy) {
        public Entry {
            dimensions = Set.copyOf(dimensions == null ? Set.of() : dimensions);
            if (dimensions.isEmpty()) throw new IllegalArgumentException("a structure needs a dimension");
            if (strategy == null) throw new IllegalArgumentException("strategy");
        }

        /** Whether this entry can be searched in the dimension; an empty dimension is unknown, never a match. */
        public boolean eligible(String dimension) {
            return dimension != null && dimensions.contains(dimension);
        }
    }

    private static final Map<String, Entry> ENTRIES = build();
    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";

    private StructurePolicy() {}

    /** The policy for an id, or empty for a structure the catalog does not describe. */
    public static Optional<Entry> get(String structureId) {
        if (structureId == null || structureId.isBlank()) {
            return Optional.empty();
        }
        String id = structureId.contains(":") ? structureId : "minecraft:" + structureId;
        return Optional.ofNullable(ENTRIES.get(id));
    }

    /** Dimensions the structure may be found in; empty when the id is unknown. */
    public static Set<String> dimensions(String structureId) {
        return get(structureId).map(Entry::dimensions).orElse(Set.of());
    }

    /** Whether the structure can be searched in the dimension; unknown dimensions and ids are false. */
    public static boolean eligible(String structureId, String dimension) {
        return get(structureId).map(entry -> entry.eligible(dimension)).orElse(false);
    }

    /** Every id the policy describes, sorted. */
    public static List<String> ids() {
        return ENTRIES.keySet().stream().sorted().toList();
    }

    private static Map<String, Entry> build() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        entries.put("minecraft:village", new Entry(Set.of(OVERWORLD), Strategy.SURFACE));
        entries.put("minecraft:pillager_outpost", new Entry(Set.of(OVERWORLD), Strategy.SURFACE));
        entries.put("minecraft:stronghold", new Entry(Set.of(OVERWORLD), Strategy.INDIRECT));
        entries.put("minecraft:nether_fortress", new Entry(Set.of(NETHER), Strategy.NETHER));
        entries.put("minecraft:bastion_remnant", new Entry(Set.of(NETHER), Strategy.NETHER));
        entries.put("minecraft:ancient_city", new Entry(Set.of(OVERWORLD), Strategy.UNDERGROUND));
        entries.put("minecraft:trial_chambers", new Entry(Set.of(OVERWORLD), Strategy.UNDERGROUND));
        entries.put("minecraft:ocean_monument", new Entry(Set.of(OVERWORLD), Strategy.OCEAN));
        entries.put("minecraft:woodland_mansion", new Entry(Set.of(OVERWORLD), Strategy.SURFACE));
        entries.put("minecraft:desert_pyramid", new Entry(Set.of(OVERWORLD), Strategy.SURFACE));
        entries.put("minecraft:jungle_temple", new Entry(Set.of(OVERWORLD), Strategy.SURFACE));
        entries.put("minecraft:swamp_hut", new Entry(Set.of(OVERWORLD), Strategy.SURFACE));
        entries.put("minecraft:ruined_portal", new Entry(Set.of(OVERWORLD, NETHER), Strategy.SURFACE));
        entries.put("minecraft:mineshaft", new Entry(Set.of(OVERWORLD, NETHER), Strategy.UNDERGROUND));
        return Map.copyOf(entries);
    }

    /** The seven kinds item 61 names, in a stable order. */
    public static List<String> requiredIds() {
        return List.of(
                "minecraft:village",
                "minecraft:pillager_outpost",
                "minecraft:stronghold",
                "minecraft:nether_fortress",
                "minecraft:bastion_remnant",
                "minecraft:ancient_city",
                "minecraft:trial_chambers");
    }

    /** Normalizes an id the same way {@link #get} does; empty for blank input. */
    public static String normalize(String structureId) {
        if (structureId == null || structureId.isBlank()) return "";
        String stripped = structureId.strip().toLowerCase(Locale.ROOT);
        return stripped.contains(":") ? stripped : "minecraft:" + stripped;
    }
}
