package dev.friendai.task.autonomy;

import dev.friendai.foundation.places.Place;
import dev.friendai.foundation.places.PlaceProtection;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.task.TaskContext;
import net.minecraft.core.BlockPos;

/**
 * Keeps free time's hands off the places the owner cares about: an autonomous job never breaks a block inside a
 * known place, takes only from containers inside one, and never builds over one. Owner orders are not affected, and
 * a dig site the companion registered itself is a work area rather than owner property — see
 * {@link PlaceProtection}.
 */
public final class PlaceGuard {
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
        if (!c.autonomous() || !enabled(c)) {
            return null;
        }
        return PlaceProtection.blocker(placesHere(c), dimension(c), toPos(pos), tool(c), true)
                .orElse(null);
    }

    /** True when {@code pos} lies inside an owner-known place in the companion's dimension. */
    public static boolean insideAnyPlace(TaskContext context, BlockPos pos) {
        return placesHere(context).stream().anyMatch(place -> inside(place, pos));
    }

    /** The first known place the box from {@code min} to {@code max} touches, or null. */
    public static Place overlapping(TaskContext context, BlockPos min, BlockPos max) {
        String tool = tool(context);
        for (Place place : placesHere(context)) {
            if (PlaceProtection.exempt(place, tool)) {
                continue;
            }
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
    public static boolean enabled(TaskContext context) {
        return context.level().getServer() != null
                && context.serverSettings().autonomy().protectPlaces();
    }

    private static String tool(TaskContext context) {
        return context.execution() == null ? "" : context.execution().currentToolName();
    }

    private static String dimension(TaskContext context) {
        return context.level().dimension().identifier().toString();
    }

    private static java.util.List<Place> placesHere(TaskContext context) {
        var companion = context.companion();
        if (companion.ownerUuid() == null) {
            return java.util.List.of();
        }
        return context.placeService().book(companion).all().stream()
                .filter(p -> p.dimension().equals(dimension(context)))
                .toList();
    }

    private static Pos toPos(BlockPos pos) {
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }

    private static boolean inside(Place place, BlockPos pos) {
        return place.contains(toPos(pos));
    }
}
