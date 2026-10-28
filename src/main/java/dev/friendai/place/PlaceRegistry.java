package dev.friendai.place;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * One owner's places: id-keyed records plus a lookup from every name and alias within its dimension. Pure and
 * mutable; the store decides when to persist. The revision changes on every real mutation so the menu and the
 * writer thread can tell a changed book from an unchanged one.
 */
public final class PlaceRegistry {

    private final PlaceLimits limits;
    private final TreeMap<Long, Place> byId = new TreeMap<>();
    /** {@link PlaceNames#key(String, String)} of every name and alias to the id that owns it. */
    private final Map<String, Long> keys = new HashMap<>();

    private long nextId = 1;
    private long revision;
    private boolean dirty;

    public PlaceRegistry(PlaceLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Creates a place, or updates the existing one whose name or alias matches the values' name in the same
     * dimension. An update keeps the id, creation tick, visits, author and source, refreshes the geometry and
     * text, unions the aliases and sets {@code updatedTick = tick}. Throws {@link IllegalArgumentException} when
     * an alias belongs to another place, an alias or the place does not fit the limits, or the registry is full.
     */
    public Place remember(PlaceValues values, long tick) {
        if (values == null) {
            throw new IllegalArgumentException("A place needs values.");
        }
        PlaceNames.normalize(values.name()); // validates; the lookup form is derived per key
        String name = values.name().strip();
        String dimension = requireDimension(values.dimension());
        Set<String> aliases = typedAliases(values.aliases());

        String nameKey = PlaceNames.key(name, dimension);
        Long matchedId = keys.get(nameKey);
        Place existing = matchedId == null ? null : byId.get(matchedId);
        if (existing == null) {
            matchedId = null;
        }

        for (String alias : aliases) {
            Long ownerId = keys.get(PlaceNames.key(alias, dimension));
            if (ownerId != null && !ownerId.equals(matchedId)) {
                Place owner = byId.get(ownerId);
                throw new IllegalArgumentException("The alias '" + alias + "' already names place #" + ownerId
                        + " '" + owner.name() + "'.");
            }
        }

        if (existing == null) {
            if (byId.size() >= limits.maxPlaces()) {
                throw new IllegalArgumentException(
                        "no room for more places (" + limits.maxPlaces() + "); forget one first");
            }
            Place place = new Place(
                    nextId, name, values.kind(), values.label(), dimension, values.anchor(), values.radius(),
                    aliases, values.source(), values.author(), tick, tick, 0, 0, values.note(),
                    values.below(), values.above());
            checkLimits(place);
            nextId++;
            byId.put(place.id(), place);
            addKeys(place);
            dirty = true;
            revision++;
            return place;
        }

        Map<String, String> merged = new LinkedHashMap<>();
        for (String alias : existing.aliases()) {
            merged.putIfAbsent(PlaceNames.normalize(alias), alias);
        }
        for (String alias : aliases) {
            merged.putIfAbsent(PlaceNames.normalize(alias), alias);
        }
        Place updated = new Place(
                existing.id(), existing.name(), values.kind(), values.label(), existing.dimension(),
                values.anchor(), values.radius(), new LinkedHashSet<>(merged.values()), existing.source(),
                existing.author(), existing.createdTick(), tick, existing.lastVisitTick(), existing.visits(),
                values.note(), values.below(), values.above());
        checkLimits(updated);
        if (updated.equals(existing)) {
            return existing;
        }
        byId.put(updated.id(), updated);
        addKeys(updated);
        dirty = true;
        revision++;
        return updated;
    }

    /** Exact, case-insensitive lookup by name or alias within one dimension. */
    public Optional<Place> find(String nameOrAlias, String dimension) {
        if (nameOrAlias == null || nameOrAlias.isBlank() || dimension == null || dimension.isBlank()) {
            return Optional.empty();
        }
        try {
            Long id = keys.get(PlaceNames.key(nameOrAlias, dimension));
            return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
        } catch (IllegalArgumentException notAName) {
            return Optional.empty();
        }
    }

    public Optional<Place> byId(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Every place, by id ascending. */
    public List<Place> all() {
        return List.copyOf(byId.values());
    }

    /** Counts one visit and stamps it. The creation tick and the update tick are untouched. */
    public Place visit(long id, long tick) {
        Place existing = require(id);
        Place updated = new Place(
                existing.id(), existing.name(), existing.kind(), existing.label(), existing.dimension(),
                existing.anchor(), existing.radius(), existing.aliases(), existing.source(), existing.author(),
                existing.createdTick(), existing.updatedTick(), tick, existing.visits() + 1, existing.note(),
                existing.below(), existing.above());
        byId.put(updated.id(), updated);
        dirty = true;
        revision++;
        return updated;
    }

    /** Renames a place, keeping its id and aliases, and rebuilds the lookup. Throws when the name is taken. */
    public Place rename(long id, String newName, long tick) {
        Place existing = require(id);
        String name = newName == null ? null : newName.strip();
        Long ownerId = keys.get(PlaceNames.key(name, existing.dimension()));
        if (ownerId != null && ownerId.longValue() != id) {
            Place owner = byId.get(ownerId);
            throw new IllegalArgumentException("'" + name + "' already names place #" + ownerId + " '"
                    + owner.name() + "'.");
        }
        if (name.equals(existing.name())) {
            return existing;
        }
        Place updated = new Place(
                existing.id(), name, existing.kind(), existing.label(), existing.dimension(), existing.anchor(),
                existing.radius(), existing.aliases(), existing.source(), existing.author(),
                existing.createdTick(), tick, existing.lastVisitTick(), existing.visits(), existing.note(),
                existing.below(), existing.above());
        checkLimits(updated);
        keys.entrySet().removeIf(entry -> entry.getValue().longValue() == id);
        byId.put(updated.id(), updated);
        addKeys(updated);
        dirty = true;
        revision++;
        return updated;
    }

    /** Forgets a place and its lookup entries. Ids are never reused. */
    public boolean remove(long id) {
        Place existing = byId.remove(id);
        if (existing == null) {
            return false;
        }
        keys.entrySet().removeIf(entry -> entry.getValue().longValue() == id);
        dirty = true;
        revision++;
        return true;
    }

    /** Changes on every mutation; the menu compares it with the last revision it sent. Not persisted. */
    public long revision() {
        return revision;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    /** An immutable file view of this registry, in id order. */
    public PlaceFile snapshot() {
        List<PlaceFile.Record> records = new ArrayList<>(byId.size());
        for (Place place : byId.values()) {
            records.add(PlaceFile.Record.of(place));
        }
        return new PlaceFile(nextId, records);
    }

    /**
     * Rebuilds a registry from a file. The file is validated first; ids and {@code nextId} are taken as stored, so
     * a remembered place can never reuse an id. The restored book starts clean, like a freshly loaded one.
     */
    public static PlaceRegistry restore(PlaceFile file, PlaceLimits limits) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(limits, "limits");
        PlaceRegistry registry = new PlaceRegistry(limits);
        for (Place place : file.validated(limits)) {
            registry.byId.put(place.id(), place);
            registry.addKeys(place);
        }
        registry.nextId = file.nextId();
        return registry;
    }

    private Place require(long id) {
        Place place = byId.get(id);
        if (place == null) {
            throw new IllegalArgumentException("There is no place [" + id + "].");
        }
        return place;
    }

    private void addKeys(Place place) {
        keys.put(PlaceNames.key(place.name(), place.dimension()), place.id());
        for (String alias : place.aliases()) {
            keys.put(PlaceNames.key(alias, place.dimension()), place.id());
        }
    }

    private void checkLimits(Place place) {
        if (place.name().length() > limits.maxNameChars()) {
            throw new IllegalArgumentException("A place name is too long (" + place.name().length()
                    + " chars, max " + limits.maxNameChars() + "). Shorten it.");
        }
        if (place.aliases().size() > limits.maxAliases()) {
            throw new IllegalArgumentException(
                    "A place can have at most " + limits.maxAliases() + " aliases.");
        }
        for (String alias : place.aliases()) {
            if (alias.length() > limits.maxNameChars()) {
                throw new IllegalArgumentException("A place alias is too long (" + alias.length() + " chars, max "
                        + limits.maxNameChars() + "). Shorten it.");
            }
        }
        if (place.note().length() > limits.maxNoteChars()) {
            throw new IllegalArgumentException("A place note is too long (" + place.note().length()
                    + " chars, max " + limits.maxNoteChars() + "). Shorten it.");
        }
    }

    private static String requireDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("A place needs a dimension.");
        }
        return dimension.strip();
    }

    private static Set<String> typedAliases(Set<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return Set.of();
        }
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String alias : aliases) {
            byKey.putIfAbsent(PlaceNames.normalize(alias), alias.strip());
        }
        return new LinkedHashSet<>(byKey.values());
    }
}
