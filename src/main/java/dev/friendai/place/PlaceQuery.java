package dev.friendai.place;

import dev.friendai.pathing.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Answers "which places do I know?" for the prompt, the planner and the find tool. Pure: the caller passes its
 * own position and dimension, so the query never reaches into a world. Text matching prefers prefix matches over
 * substring matches; when both exist the substring tier is dropped, which keeps a search for "mine" from burying
 * a place literally called "mineshaft" under an "old mine".
 */
public final class PlaceQuery {

    /** Smallest useful result count; also what a non-positive request is clamped to. */
    public static final int LIMIT_MIN = 1;

    /** Largest result count the tool schema allows. */
    public static final int LIMIT_MAX = 20;

    /** What a caller that does not care passes. */
    public static final int LIMIT_DEFAULT = 5;

    private PlaceQuery() {}

    /**
     * What to look for. Every part is optional except the limit: blank text matches every place, an empty kind set
     * or dimension matches any, and {@code otherDimensions} admits places outside the companion's dimension.
     */
    public record Request(
            Optional<String> text,
            Set<PlaceKind> kinds,
            Optional<String> dimension,
            int limit,
            boolean otherDimensions) {

        public Request {
            text = text == null ? Optional.empty() : text.map(String::strip).filter(value -> !value.isEmpty());
            kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
            dimension = dimension == null
                    ? Optional.empty()
                    : dimension.map(String::strip).filter(value -> !value.isEmpty());
        }
    }

    /** One match: the place, its horizontal distance from the self position, and whether it is in the same world. */
    public record Result(Place place, double distance, boolean sameDimension) {
        public Result {
            Objects.requireNonNull(place, "place");
        }
    }

    /**
     * Filters and sorts the registry for {@code request}. Same-dimension places come first, nearest first, ties by
     * id; other-dimension places follow, most recently updated first, ties by id. A place in another dimension
     * reports distance {@code 0} with {@code sameDimension == false}. The limit is clamped to 1..20.
     */
    public static List<Result> find(PlaceRegistry registry, Request request, Pos self, String selfDimension) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(self, "self");
        if (selfDimension == null || selfDimension.isBlank()) {
            throw new IllegalArgumentException("A place query needs a self dimension.");
        }
        String selfDim = selfDimension.strip();
        String needle = request.text().map(PlaceQuery::fold).orElse("");
        if (request.text().isPresent() && needle.isEmpty()) {
            return List.of();
        }

        List<Place> matched = new ArrayList<>();
        for (Place place : registry.all()) {
            if (passesFilters(place, request, selfDim) && startsWithNeedle(place, needle)) {
                matched.add(place);
            }
        }
        if (matched.isEmpty() && !needle.isEmpty()) {
            for (Place place : registry.all()) {
                if (passesFilters(place, request, selfDim) && containsNeedle(place, needle)) {
                    matched.add(place);
                }
            }
        }

        List<Result> same = new ArrayList<>();
        List<Result> other = new ArrayList<>();
        for (Place place : matched) {
            boolean sameDimension = place.dimension().equals(selfDim);
            double distance = sameDimension
                    ? Math.hypot(place.anchor().x() - self.x(), place.anchor().z() - self.z())
                    : 0.0;
            Result result = new Result(place, distance, sameDimension);
            if (sameDimension) {
                same.add(result);
            } else {
                other.add(result);
            }
        }
        same.sort(Comparator.comparingDouble(Result::distance)
                .thenComparingLong(result -> result.place().id()));
        other.sort(Comparator.comparingLong((Result result) -> result.place().updatedTick())
                .reversed()
                .thenComparingLong(result -> result.place().id()));

        List<Result> sorted = new ArrayList<>(same.size() + other.size());
        sorted.addAll(same);
        sorted.addAll(other);
        int limit = Math.max(LIMIT_MIN, Math.min(request.limit(), LIMIT_MAX));
        return List.copyOf(sorted.subList(0, Math.min(limit, sorted.size())));
    }

    private static boolean passesFilters(Place place, Request request, String selfDim) {
        if (!request.kinds().isEmpty() && !request.kinds().contains(place.kind())) {
            return false;
        }
        if (request.dimension().isPresent() && !request.dimension().get().equals(place.dimension())) {
            return false;
        }
        return place.dimension().equals(selfDim) || request.otherDimensions();
    }

    private static boolean startsWithNeedle(Place place, String needle) {
        if (fold(place.name()).startsWith(needle) || fold(place.label()).startsWith(needle)) {
            return true;
        }
        for (String alias : place.aliases()) {
            if (fold(alias).startsWith(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsNeedle(Place place, String needle) {
        if (fold(place.name()).contains(needle) || fold(place.label()).contains(needle)) {
            return true;
        }
        for (String alias : place.aliases()) {
            if (fold(alias).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** The lookup form of a name, alias, label or needle; text the model would reject simply matches nothing. */
    private static String fold(String text) {
        if (text == null) {
            return "";
        }
        try {
            return PlaceNames.normalize(text);
        } catch (IllegalArgumentException notAName) {
            return "";
        }
    }
}
