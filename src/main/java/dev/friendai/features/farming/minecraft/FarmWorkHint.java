package dev.friendai.features.farming.minecraft;

import dev.friendai.features.farming.CellKind;
import dev.friendai.features.farming.FieldCell;
import dev.friendai.task.TaskContext;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Where the nearest farm work lies when a pass or its preview found none: a model that guessed a small radius around
 * the companion ("plant wheat" with radius 3, the grass by the water five blocks off) learns where to farm instead of
 * concluding there is no soil. Server thread only.
 */
public final class FarmWorkHint {
    /** How far a pass that found nothing looks for the nearest work to name. */
    public static final int RADIUS = 16;

    private FarmWorkHint() {}

    /** A clause to append to a "nothing to do" answer, starting with "; ", or empty when the radius already covered it. */
    public static String nearest(TaskContext c, BlockPos center, int radius, boolean till) {
        if (radius >= RADIUS) return "";
        FieldCell nearest = null;
        for (FieldCell cell :
                FarmSnapshotBuilder.build(c, center, RADIUS, Set.of()).cells()) {
            boolean work = cell.kind() == CellKind.RIPE
                    || cell.kind() == CellKind.EMPTY_SOIL
                    || till && cell.kind() == CellKind.TILLABLE;
            if (work && (nearest == null || flat(cell) < flat(nearest))) nearest = cell;
        }
        if (nearest == null) return "; nothing to farm within " + RADIUS + " blocks either";
        BlockPos at = center.offset(nearest.dx(), nearest.dy(), nearest.dz());
        String what = switch (nearest.kind()) {
            case RIPE -> "ripe crop";
            case EMPTY_SOIL -> "empty farmland";
            default -> "soil next to water to till";
        };
        return "; the nearest " + what + " is at " + at.getX() + " " + at.getY() + " " + at.getZ() + ", "
                + Math.round(Math.sqrt(flat(nearest))) + " blocks away: farm around it or with a larger radius";
    }

    private static int flat(FieldCell cell) {
        return cell.dx() * cell.dx() + cell.dz() * cell.dz();
    }
}
