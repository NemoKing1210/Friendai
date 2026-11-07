package dev.friendai.features.crafting;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Decides what one planning pass of a batch does with the material a target set still needs when a recipe requires
 * a crafting station that may be impossible where the companion is.
 *
 * <p>A raw item always comes before a station, because gathering material is progress while making a station can be
 * impossible. When the station cannot be made here it is blocked for the rest of this pass only and the demand is
 * re-planned without it, so an alternative recipe can be chosen. A later pass starts with an empty block set, because
 * the ingredients gathered in the meantime may make the station after all. When every route back to the target needs
 * a blocked station the decision names that station instead of sending the companion after material it cannot use.
 */
public final class StationFallback {
    /** What one planning pass should do next. */
    public sealed interface Decision permits Next, Blocked, Done {}

    /** Work on {@code need}; {@code candidate} is set when the station was proven obtainable during the decision. */
    public record Next(CraftPlanner.RawNeed need, String candidate) implements Decision {}

    /** No route avoids {@code stationId}: the batch cannot finish from here. */
    public record Blocked(String stationId) implements Decision {}

    /** Nothing raw is missing any more: the targets can be crafted. */
    public record Done() implements Decision {}

    private StationFallback() {}

    /**
     * Decides the next step of one planning pass.
     *
     * @param demand the material the targets still need, planned without any station blocked
     * @param stationsNearby the stations the companion can already use
     * @param stationCandidate proves one station need obtainable here: it returns the candidate item to gather, or
     *     {@code null} when the station can be neither found, carried nor made
     */
    public static Decision decide(
            List<CraftPlanner.Target> targets,
            List<CraftPlanner.RawNeed> demand,
            Map<String, Integer> inventory,
            RecipeBook book,
            Set<Station> stationsNearby,
            Function<CraftPlanner.RawNeed, String> stationCandidate) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(demand, "demand");
        Objects.requireNonNull(book, "book");
        Objects.requireNonNull(stationCandidate, "stationCandidate");
        Set<Station> blocked = EnumSet.noneOf(Station.class);
        List<CraftPlanner.RawNeed> current = demand;
        while (!current.isEmpty()) {
            CraftPlanner.RawNeed need = nextNeed(current);
            if (!need.stationNeed()) {
                return new Next(need, null);
            }
            if (blocked.contains(need.station())) {
                // The plan that avoided this station still needs it: nothing else can make the target.
                return new Blocked(stationId(book, need));
            }
            String candidate = stationCandidate.apply(need);
            if (candidate != null) {
                return new Next(need, candidate);
            }
            // Each blocked station is dropped from the next demand, so this loop asks about each station once.
            blocked.add(need.station());
            current = CraftPlanner.materialsFor(targets, inventory, book, stationsNearby, blocked);
        }
        return new Done();
    }

    /**
     * A raw item before a station: gathering material is always progress, while a station may be impossible to make
     * where the companion stands.
     */
    static CraftPlanner.RawNeed nextNeed(List<CraftPlanner.RawNeed> demand) {
        for (CraftPlanner.RawNeed need : demand) {
            if (!need.stationNeed()) {
                return need;
            }
        }
        return demand.get(0);
    }

    /** How the failure names a station: its item id when the book knows one, its own label otherwise. */
    private static String stationId(RecipeBook book, CraftPlanner.RawNeed need) {
        Station station = need.station();
        String id = station == null ? null : book.stationItem(station);
        return id == null || id.isBlank() ? need.label() : id;
    }
}
