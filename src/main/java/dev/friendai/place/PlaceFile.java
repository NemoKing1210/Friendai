package dev.friendai.place;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.friendai.features.navigation.pathing.Pos;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Versioned persistence DTO for one owner's places. {@link #decode} routes every record through the
 * {@link Place} constructor, so a loaded place passes exactly the checks a live one does, and then rejects the
 * structural damage a constructor cannot see: a wrong version, duplicate ids, a non-monotonic {@code nextId} and
 * a name or alias claimed by two places of the same dimension. A violation is an
 * {@link IllegalArgumentException}; the store backs the file up and starts empty.
 */
public final class PlaceFile {
    /**
     * Version 2 added the vertical band ({@code below}/{@code above}). A version-1 file decodes with the band
     * defaults, so an old world keeps working and stops protecting the rock under every home.
     */
    public static final int CURRENT_VERSION = 2;

    /** The oldest file version this build still reads. */
    public static final int MIN_VERSION = 1;

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private int version;
    private long nextId;
    private List<Record> places;

    private PlaceFile() {}

    PlaceFile(long nextId, List<Record> places) {
        this.version = CURRENT_VERSION;
        this.nextId = nextId;
        this.places = new ArrayList<>(places);
    }

    /** The file for {@code registry}, in id order. */
    public static PlaceFile encode(PlaceRegistry registry) {
        return Objects.requireNonNull(registry, "registry").snapshot();
    }

    /** Decodes one owner's file, bounded and validated; never returns a partially loaded registry. */
    public static PlaceRegistry decode(String json, PlaceLimits limits) {
        Objects.requireNonNull(limits, "limits");
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("A place file cannot be empty.");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > limits.maxFileBytes()) {
            throw new IllegalArgumentException("A place file exceeds " + limits.maxFileBytes() + " bytes.");
        }
        PlaceFile parsed;
        try {
            parsed = GSON.fromJson(json, PlaceFile.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("A place file is not valid JSON.", e);
        }
        if (parsed == null) {
            throw new IllegalArgumentException("A place file cannot be empty.");
        }
        return decode(parsed, limits);
    }

    /** Restores a registry from an in-memory snapshot, validating it exactly like a file. */
    public static PlaceRegistry decode(PlaceFile file, PlaceLimits limits) {
        return PlaceRegistry.restore(file, limits);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public void validate(PlaceLimits limits) {
        validated(limits);
    }

    long nextId() {
        return nextId;
    }

    /** Validates every record and materializes it as a {@link Place}. */
    List<Place> validated(PlaceLimits limits) {
        Objects.requireNonNull(limits, "limits");
        if (version < MIN_VERSION || version > CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported place file version: " + version + ".");
        }
        if (places == null) {
            throw new IllegalArgumentException("A place file needs a places list.");
        }
        if (places.size() > limits.maxPlaces()) {
            throw new IllegalArgumentException(
                    "A place file holds " + places.size() + " places, max " + limits.maxPlaces() + ".");
        }
        if (nextId < 1) {
            throw new IllegalArgumentException("A place file needs a positive nextId.");
        }
        List<Place> parsed = new ArrayList<>(places.size());
        Set<Long> ids = new HashSet<>();
        Map<String, Long> keys = new LinkedHashMap<>();
        long largestId = 0;
        for (Record record : places) {
            if (record == null) {
                throw new IllegalArgumentException("A place file holds a null record.");
            }
            Place place = record.toPlace(version);
            if (!ids.add(place.id())) {
                throw new IllegalArgumentException("Duplicate place id [" + place.id() + "].");
            }
            checkLimits(place, limits);
            if (place.createdTick() < 0
                    || place.updatedTick() < place.createdTick()
                    || place.lastVisitTick() < 0
                    || place.visits() < 0) {
                throw new IllegalArgumentException("Place #" + place.id() + " has invalid ticks or visits.");
            }
            claim(keys, place.name(), place);
            for (String alias : place.aliases()) {
                claim(keys, alias, place);
            }
            largestId = Math.max(largestId, place.id());
            parsed.add(place);
        }
        if (nextId <= largestId) {
            throw new IllegalArgumentException(
                    "nextId " + nextId + " must exceed the largest place id " + largestId + ".");
        }
        return List.copyOf(parsed);
    }

    private static void claim(Map<String, Long> keys, String name, Place place) {
        String key = PlaceNames.key(name, place.dimension());
        Long owner = keys.putIfAbsent(key, place.id());
        if (owner != null && owner.longValue() != place.id()) {
            throw new IllegalArgumentException("'" + name + "' names places [" + owner + "] and [" + place.id()
                    + "] in " + place.dimension() + ".");
        }
    }

    private static void checkLimits(Place place, PlaceLimits limits) {
        if (place.name().length() > limits.maxNameChars()) {
            throw new IllegalArgumentException(
                    "Place #" + place.id() + " has a name longer than " + limits.maxNameChars() + " chars.");
        }
        if (place.label().length() > PlaceLimits.LABEL_MAX) {
            throw new IllegalArgumentException(
                    "Place #" + place.id() + " has a label longer than " + PlaceLimits.LABEL_MAX + " chars.");
        }
        if (place.aliases().size() > limits.maxAliases()) {
            throw new IllegalArgumentException(
                    "Place #" + place.id() + " has more than " + limits.maxAliases() + " aliases.");
        }
        for (String alias : place.aliases()) {
            if (alias.length() > limits.maxNameChars()) {
                throw new IllegalArgumentException(
                        "Place #" + place.id() + " has an alias longer than " + limits.maxNameChars() + " chars.");
            }
        }
        if (place.note().length() > limits.maxNoteChars()) {
            throw new IllegalArgumentException(
                    "Place #" + place.id() + " has a note longer than " + limits.maxNoteChars() + " chars.");
        }
    }

    /** One stored place; field names and order mirror the file format. */
    public static final class Record {
        private long id;
        private String name;
        private String kind;
        private String label;
        private String dimension;
        private int x;
        private int y;
        private int z;
        private int radius;
        private Integer below;
        private Integer above;
        private List<String> aliases;
        private String source;
        private String author;
        private long created;
        private long updated;
        private long lastVisit;
        private int visits;
        private String note;

        public Record() {}

        private Record(Place place) {
            this.id = place.id();
            this.name = place.name();
            this.kind = place.kind().name();
            this.label = place.label();
            this.dimension = place.dimension();
            this.x = place.anchor().x();
            this.y = place.anchor().y();
            this.z = place.anchor().z();
            this.radius = place.radius();
            this.below = place.below();
            this.above = place.above();
            this.aliases = new ArrayList<>(place.aliases());
            this.source = place.source().name();
            this.author = place.author() == null ? null : place.author().toString();
            this.created = place.createdTick();
            this.updated = place.updatedTick();
            this.lastVisit = place.lastVisitTick();
            this.visits = place.visits();
            this.note = place.note();
        }

        static Record of(Place place) {
            return new Record(place);
        }

        Place toPlace(int version) {
            int bandBelow = version >= 2 && below != null ? below : Place.defaultBelow(radius);
            int bandAbove = version >= 2 && above != null ? above : Place.defaultAbove(radius);
            return new Place(
                    id,
                    name,
                    kind(),
                    label,
                    dimension,
                    new Pos(x, y, z),
                    radius,
                    aliases == null ? Set.of() : new LinkedHashSet<>(aliases),
                    source(),
                    author(),
                    created,
                    updated,
                    lastVisit,
                    visits,
                    note,
                    bandBelow,
                    bandAbove);
        }

        private PlaceKind kind() {
            if (kind == null) {
                throw new IllegalArgumentException("A place needs a kind.");
            }
            try {
                return PlaceKind.valueOf(kind.strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown place kind: " + kind + ".", e);
            }
        }

        private PlaceSource source() {
            if (source == null) {
                throw new IllegalArgumentException("A place needs a source.");
            }
            try {
                return PlaceSource.valueOf(source.strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown place source: " + source + ".", e);
            }
        }

        private UUID author() {
            if (author == null) {
                return null;
            }
            try {
                return UUID.fromString(author.strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid place author: " + author + ".", e);
            }
        }
    }
}
