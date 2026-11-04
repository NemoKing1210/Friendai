package dev.friendai.features.places;

import dev.friendai.foundation.spatial.Pos;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Which owner-known place refuses an autonomous job: the pure half of the free-time safety rule. A place blocks work
 * in its box unless it is a farm the job is farming, or a dig site the companion registered itself
 * ({@link PlaceSource#MINE}): that is a work area, not owner property, and protecting it would forbid the very work it
 * records. Owner orders are never blocked.
 */
public final class PlaceProtection {
    /** Harvesting inside a farm is what these tools are for. */
    public static final Set<String> FARM_TOOLS = Set.of("farm", "tend_farm", "use_bonemeal");
    /** These break only the crafting or cooking stations they placed themselves. */
    public static final Set<String> STATION_TOOLS =
            Set.of("collect_smelted", "collect_station", "craft_item", "smelt_items", "cook_food");

    private PlaceProtection() {}

    /**
     * The first place that refuses work at {@code pos}, or empty when none does. {@code dimension} and {@code pos}
     * come from the running job; {@code tool} is its tool name and {@code autonomous} says whether free time owns it.
     */
    public static Optional<Place> blocker(
            List<Place> places, String dimension, Pos pos, String tool, boolean autonomous) {
        if (!autonomous || places == null || pos == null) {
            return Optional.empty();
        }
        String name = tool == null ? "" : tool;
        if (STATION_TOOLS.contains(name)) {
            return Optional.empty();
        }
        String here = dimension == null ? "" : dimension;
        for (Place place : places) {
            if (place == null || !place.dimension().equals(here) || !place.contains(pos)) {
                continue;
            }
            if (exempt(place, name)) {
                continue;
            }
            return Optional.of(place);
        }
        return Optional.empty();
    }

    /** A place this job may work in even though it covers the cell. */
    public static boolean exempt(Place place, String tool) {
        Objects.requireNonNull(place, "place");
        if (place.source() == PlaceSource.MINE) {
            // The companion's own dig site is a work area; a mine the owner placed stays protected (COMMAND/MANUAL).
            return true;
        }
        return place.kind() == PlaceKind.FARM && FARM_TOOLS.contains(tool == null ? "" : tool);
    }
}
