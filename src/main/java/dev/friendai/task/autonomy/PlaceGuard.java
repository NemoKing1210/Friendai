package dev.friendai.task.autonomy;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.place.Place;
import dev.friendai.place.PlaceKind;
import dev.friendai.place.PlaceService;
import dev.friendai.task.TaskContext;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Keeps free time's hands off the places the owner cares about: an autonomous job never breaks a block inside a
 * known place, takes only from containers inside one, and never builds over one. Owner orders are not affected.
 */
public final class PlaceGuard {
    /** Harvesting inside a farm is what these tools are for. */
    static final Set<String> FARM_TOOLS = Set.of("farm", "tend_farm", "use_bonemeal");
    /** These break only the crafting or cooking stations they placed themselves. */
    static final Set<String> STATION_TOOLS =
            Set.of("collect_smelted", "collect_station", "craft_item", "smelt_items", "cook_food");

    private PlaceGuard() {}

    /** True when the running autonomous job must not break the block at {@code pos}. */
    public static boolean protects(TaskContext c, BlockPos pos) {
        return protecting(c, pos) != null;
    }

    /**
     * The place that protects {@code pos} from this job, or null when none does. The caller uses the name and
     * kind in its refusal, so the model learns which place refused instead of a bare "protected".
     */
    public static Place protecting(TaskContext c, BlockPos pos) {
        if (!c.autonomous() || !enabled(c.companion())) {
            return null;
        }
        String tool = c.execution() == null ? "" : c.execution().currentToolName();
        if (STATION_TOOLS.contains(tool)) {
            return null;
        }
        for (Place place : placesHere(c.companion())) {
            if (inside(place, pos) && !(place.kind() == PlaceKind.FARM && FARM_TOOLS.contains(tool))) {
                return place;
            }
        }
        return null;
    }

    /** True when {@code pos} lies inside any known place in the companion's dimension. */
    public static boolean insideAnyPlace(CompanionEntity companion, BlockPos pos) {
        return placesHere(companion).stream().anyMatch(place -> inside(place, pos));
    }

    /** True when the box from {@code min} to {@code max} touches any known place in the companion's dimension. */
    public static boolean overlapsAnyPlace(CompanionEntity companion, BlockPos min, BlockPos max) {
        return overlapping(companion, min, max) != null;
    }

    /** The first known place the box from {@code min} to {@code max} touches, or null. */
    public static Place overlapping(CompanionEntity companion, BlockPos min, BlockPos max) {
        for (Place place : placesHere(companion)) {
            int r = place.radius();
            if (max.getX() >= place.anchor().x() - r
                    && min.getX() <= place.anchor().x() + r
                    && max.getY() >= place.anchor().y() - place.below()
                    && min.getY() <= place.anchor().y() + place.above()
                    && max.getZ() >= place.anchor().z() - r
                    && min.getZ() <= place.anchor().z() + r) {
                return place;
            }
        }
        return null;
    }

    /** Whether the server protects places from free time. */
    public static boolean enabled(CompanionEntity companion) {
        return companion.level().getServer() != null
                && FriendaiServerState.get(companion.level().getServer()).config().autonomy.protectPlaces;
    }

    private static java.util.List<Place> placesHere(CompanionEntity companion) {
        if (companion.ownerUuid() == null) {
            return java.util.List.of();
        }
        String dimension = companion.level().dimension().identifier().toString();
        return PlaceService.book(companion).all().stream()
                .filter(p -> p.dimension().equals(dimension))
                .toList();
    }

    private static boolean inside(Place place, BlockPos pos) {
        return place.contains(new dev.friendai.features.navigation.pathing.Pos(pos.getX(), pos.getY(), pos.getZ()));
    }
}
