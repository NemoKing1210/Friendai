package dev.friendai.knowledge.search;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.friendai.pathing.Pos;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * What a search is looking for. Five kinds share one API so "find a village" and "find wood" differ in data, not in
 * code. This layer is pure: the world facts a kind needs arrive as a {@link CandidateFacts} snapshot built by a
 * Minecraft adapter, and every kind answers exactly one question — what counts as found.
 */
public sealed interface TargetKind permits TargetKind.BlockFamily, TargetKind.Structure, TargetKind.Biome,
        TargetKind.Entity, TargetKind.Place {

    String kind();

    /** LLM-facing one-liner, e.g. {@code the minecraft:village structure}. */
    String label();

    /** How far from the origin a find is still believable. */
    int plausibleRadius();

    /** Stable key for coverage, ledger dedupe and per-kind statistics. */
    String coverageKey();

    /** Dimensions this target can be found in; empty means every dimension. */
    Set<String> dimensions();

    boolean matches(CandidateFacts facts);

    JsonObject toJson();

    /** What an adapter observed at one candidate position. Sets are copied; no Minecraft types cross this boundary. */
    record CandidateFacts(Pos position, String dimension, String biomeId, Set<String> visibleBlockIds,
                          Set<String> knownBlockIds, Set<String> visibleEntityIds, Set<String> placeNames,
                          Map<Pos, String> visibleBlockLocations) {
        /** Facts without per-block positions; a structure then falls back to a marker count. */
        public CandidateFacts(Pos position, String dimension, String biomeId, Set<String> visibleBlockIds,
                              Set<String> knownBlockIds, Set<String> visibleEntityIds, Set<String> placeNames) {
            this(position, dimension, biomeId, visibleBlockIds, knownBlockIds, visibleEntityIds, placeNames, Map.of());
        }

        public CandidateFacts {
            Objects.requireNonNull(position, "position");
            if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("dimension must not be blank");
            biomeId = biomeId == null ? "" : biomeId;
            visibleBlockIds = Set.copyOf(visibleBlockIds == null ? Set.of() : visibleBlockIds);
            knownBlockIds = Set.copyOf(knownBlockIds == null ? Set.of() : knownBlockIds);
            visibleEntityIds = Set.copyOf(visibleEntityIds == null ? Set.of() : visibleEntityIds);
            placeNames = Set.copyOf(placeNames == null ? Set.of() : placeNames);
            visibleBlockLocations = Map.copyOf(visibleBlockLocations == null ? Map.of() : visibleBlockLocations);
        }
    }

    /** How close markers must stand to be one structure rather than two unrelated sightings. */
    int STRUCTURE_CLUSTER_RADIUS = 24;

    /** A block family resolved from an item, block or tag; the strategy profile keeps today's behaviour. */
    record BlockFamily(String family, Set<String> blockIds, ResourceSearchProfile profile) implements TargetKind {
        public BlockFamily {
            if (family == null || family.isBlank()) throw new IllegalArgumentException("family must not be blank");
            blockIds = Set.copyOf(blockIds == null ? Set.of() : blockIds);
            Objects.requireNonNull(profile, "profile");
        }

        @Override public String kind() { return "resource"; }
        @Override public String label() { return family; }
        @Override public int plausibleRadius() { return 64; }
        @Override public String coverageKey() { return family; }
        @Override public Set<String> dimensions() { return profile.dimensions(); }

        @Override public boolean matches(CandidateFacts facts) {
            return blockIds.stream().anyMatch(id -> facts.visibleBlockIds().contains(id) || facts.knownBlockIds().contains(id));
        }

        @Override public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("kind", kind());
            json.addProperty("family", family);
            json.add("block_ids", strings(blockIds));
            json.add("dimensions", strings(profile.dimensions()));
            if (profile.targetY().isPresent()) json.addProperty("target_y", profile.targetY().getAsInt());
            JsonArray strategies = new JsonArray();
            profile.strategies().stream().sorted().forEach(strategy -> strategies.add(strategy.name()));
            json.add("strategies", strategies);
            return json;
        }
    }

    /** A generated structure found by seeing one of its marker blocks; no map locator is consulted. */
    record Structure(String id, Set<String> markers, int minimumMarkers, int radius) implements TargetKind {
        public Structure {
            id = requireId(id, "structure id");
            markers = Set.copyOf(markers == null ? Set.of() : markers);
            if (markers.isEmpty()) throw new IllegalArgumentException("a structure target needs at least one marker");
            if (minimumMarkers < 1 || minimumMarkers > markers.size())
                throw new IllegalArgumentException("minimumMarkers must be within the marker count");
            if (radius < 1 || radius > 2_048) throw new IllegalArgumentException("radius must be between 1 and 2048");
        }

        @Override public String kind() { return "structure"; }
        @Override public String label() { return "the " + id + " structure"; }
        @Override public int plausibleRadius() { return radius; }
        @Override public String coverageKey() { return "structure:" + id; }
        @Override public Set<String> dimensions() { return Set.of(); }

        @Override public boolean matches(CandidateFacts facts) {
            if (!facts.visibleBlockLocations().isEmpty()) {
                List<Pos> hits = new ArrayList<>();
                facts.visibleBlockLocations().forEach((pos, id) -> {
                    if (markers.contains(id)) hits.add(pos);
                });
                for (Pos pivot : hits) {
                    int cluster = 0;
                    for (Pos other : hits) {
                        double dx = (double) pivot.x() - other.x(), dy = (double) pivot.y() - other.y(),
                                dz = (double) pivot.z() - other.z();
                        if (dx * dx + dy * dy + dz * dz <= STRUCTURE_CLUSTER_RADIUS * STRUCTURE_CLUSTER_RADIUS) cluster++;
                    }
                    if (cluster >= minimumMarkers) return true;
                }
                return false;
            }
            long seen = markers.stream().filter(facts.visibleBlockIds()::contains).count();
            return seen >= minimumMarkers;
        }

        @Override public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("kind", kind());
            json.addProperty("id", id);
            json.add("markers", strings(markers));
            json.addProperty("minimum_markers", minimumMarkers);
            json.addProperty("radius", radius);
            return json;
        }
    }

    /** A biome id; the companion must stand in it, not merely look at it. */
    record Biome(String id, int radius) implements TargetKind {
        public Biome {
            id = requireId(id, "biome id");
            if (radius < 1 || radius > 2_048) throw new IllegalArgumentException("radius must be between 1 and 2048");
        }

        @Override public String kind() { return "biome"; }
        @Override public String label() { return "the " + id + " biome"; }
        @Override public int plausibleRadius() { return radius; }
        @Override public String coverageKey() { return "biome:" + id; }
        @Override public Set<String> dimensions() { return Set.of(); }

        @Override public boolean matches(CandidateFacts facts) { return id.equals(facts.biomeId()); }

        @Override public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("kind", kind());
            json.addProperty("id", id);
            json.addProperty("radius", radius);
            return json;
        }
    }

    /** A living entity type the companion can see, such as a villager or a cow. */
    record Entity(String id, int radius) implements TargetKind {
        public Entity {
            id = requireId(id, "entity id");
            if (radius < 1 || radius > 2_048) throw new IllegalArgumentException("radius must be between 1 and 2048");
        }

        @Override public String kind() { return "entity"; }
        @Override public String label() { return "a " + id; }
        @Override public int plausibleRadius() { return radius; }
        @Override public String coverageKey() { return "entity:" + id; }
        @Override public Set<String> dimensions() { return Set.of(); }

        @Override public boolean matches(CandidateFacts facts) { return facts.visibleEntityIds().contains(id); }

        @Override public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("kind", kind());
            json.addProperty("id", id);
            json.addProperty("radius", radius);
            return json;
        }
    }

    /** A place from the owner's place book; arriving inside its footprint is the find. */
    record Place(String name, String dimension, int radius) implements TargetKind {
        public Place {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("place name must not be blank");
            name = name.strip();
            if (radius < 1 || radius > 2_048) throw new IllegalArgumentException("radius must be between 1 and 2048");
        }

        @Override public String kind() { return "place"; }
        @Override public String label() { return "the place " + name; }
        @Override public int plausibleRadius() { return radius; }
        @Override public String coverageKey() { return "place:" + name.toLowerCase(Locale.ROOT); }
        @Override public Set<String> dimensions() { return dimension == null || dimension.isBlank() ? Set.of() : Set.of(dimension); }

        @Override public boolean matches(CandidateFacts facts) {
            return facts.placeNames().stream().anyMatch(known -> known.equalsIgnoreCase(name));
        }

        @Override public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("kind", kind());
            json.addProperty("name", name);
            if (dimension != null && !dimension.isBlank()) json.addProperty("dimension", dimension);
            json.addProperty("radius", radius);
            return json;
        }
    }

    /** Rejects an unknown kind with a message instead of silently treating it as "find nothing". */
    static TargetKind parse(JsonObject json) {
        Objects.requireNonNull(json, "json");
        if (!json.has("kind") || json.get("kind").isJsonNull()) throw new IllegalArgumentException("target has no kind");
        String kind = json.get("kind").getAsString();
        return switch (kind) {
            case "resource" -> parseBlockFamily(json);
            case "structure" -> new Structure(string(json, "id"), strings(json, "markers"),
                    integer(json, "minimum_markers", 1), integer(json, "radius", 64));
            case "biome" -> new Biome(string(json, "id"), integer(json, "radius", 64));
            case "entity" -> new Entity(string(json, "id"), integer(json, "radius", 32));
            case "place" -> new Place(string(json, "name"),
                    json.has("dimension") && !json.get("dimension").isJsonNull() ? json.get("dimension").getAsString() : null,
                    integer(json, "radius", 64));
            default -> throw new IllegalArgumentException("unknown target kind: " + kind);
        };
    }

    private static TargetKind parseBlockFamily(JsonObject json) {
        String family = string(json, "family");
        Set<String> blockIds = strings(json, "block_ids");
        Set<String> dimensions = strings(json, "dimensions");
        OptionalInt targetY = json.has("target_y") && !json.get("target_y").isJsonNull()
                ? OptionalInt.of(json.get("target_y").getAsInt()) : OptionalInt.empty();
        Set<ResourceSearchProfile.Strategy> strategies = new LinkedHashSet<>();
        if (json.has("strategies")) {
            for (var value : json.getAsJsonArray("strategies")) {
                strategies.add(ResourceSearchProfile.Strategy.valueOf(value.getAsString()));
            }
        }
        return new BlockFamily(family, blockIds, new ResourceSearchProfile(family, dimensions, targetY, strategies));
    }

    private static String string(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) throw new IllegalArgumentException("target is missing " + field);
        String value = json.get(field).getAsString();
        if (value.isBlank()) throw new IllegalArgumentException("target " + field + " must not be blank");
        return value;
    }

    private static int integer(JsonObject json, String field, int fallback) {
        return json.has(field) && !json.get(field).isJsonNull() ? json.get(field).getAsInt() : fallback;
    }

    private static Set<String> strings(JsonObject json, String field) {
        Set<String> result = new LinkedHashSet<>();
        if (!json.has(field) || json.get(field).isJsonNull()) return result;
        for (var value : json.getAsJsonArray(field)) result.add(value.getAsString());
        return result;
    }

    private static JsonArray strings(Set<String> values) {
        JsonArray array = new JsonArray();
        values.stream().sorted().forEach(array::add);
        return array;
    }

    private static String requireId(String id, String what) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException(what + " must not be blank");
        return id.contains(":") ? id : "minecraft:" + id;
    }
}
