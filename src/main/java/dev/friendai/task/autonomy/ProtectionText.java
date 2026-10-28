package dev.friendai.task.autonomy;

import dev.friendai.place.Place;
import dev.friendai.task.TaskContext;
import java.util.Locale;
import net.minecraft.core.BlockPos;

/**
 * Names what refused an interaction that is not mining: the server's own protection or a place the owner cares
 * about. Direct {@code hands().canInteract} checks used to answer with a bare "is protected"; the sentence here
 * carries the cause, and a place additionally carries its kind and name.
 */
public final class ProtectionText {
    private ProtectionText() {}

    /** The cause, phrased to follow a coordinate: {@code "10 64 -3 is " + describe(...)}. */
    public static String describe(TaskContext c, BlockPos pos) {
        if (!c.companion().hands().canInteract(pos)) {
            return "protected by the server (spawn protection or a world rule)";
        }
        Place place = PlaceGuard.protecting(c, pos);
        if (place != null) {
            return "inside the " + place.kind().name().toLowerCase(Locale.ROOT) + " place \"" + place.name() + "\" (r="
                    + place.radius() + ")";
        }
        return "protected";
    }
}
