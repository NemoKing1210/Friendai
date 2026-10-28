package dev.friendai.task.builtin;

import dev.friendai.debug.DebugTarget;
import dev.friendai.debug.WorkSet;
import dev.friendai.pathing.Pos;
import dev.friendai.place.Place;
import dev.friendai.place.PlaceCandidate;
import dev.friendai.place.PlaceKind;
import dev.friendai.place.PlaceNames;
import dev.friendai.place.PlaceSource;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.farming.FarmPass;
import dev.friendai.task.farming.FarmReport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/** One pass of harvesting, replanting, sowing and (optionally) tilling around a point. */
public final class FarmTask implements Task {
    private final BlockPos center;
    private final int radius;
    private final @Nullable Item crop;
    private final boolean till;
    private final boolean boneMeal;
    private FarmPass pass;
    private String summary = "";
    /** The dimension the pass ran in; a place is remembered where the work happened. */
    private String dimension = "";

    public FarmTask(BlockPos center, int radius, @Nullable Item crop, boolean till) {
        this(center, radius, crop, till, false);
    }

    public FarmTask(BlockPos center, int radius, @Nullable Item crop, boolean till, boolean boneMeal) {
        this.boneMeal = boneMeal;
        this.center = center;
        this.radius = radius;
        this.crop = crop;
        this.till = till;
    }

    @Override
    public String type() {
        return "farm";
    }

    @Override
    public void start(TaskContext c) {
        dimension = c.level().dimension().identifier().toString();
        pass = new FarmPass(center, radius, crop, till, boneMeal);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (pass == null || !pass.tick(c)) {
            return TaskStatus.RUNNING;
        }
        FarmReport report = pass.report();
        summary = report.text(radius);
        boolean nothing = report.harvested().isEmpty()
                && report.replanted() == 0
                && report.planted() == 0
                && report.tilled() == 0;
        return nothing ? TaskStatus.FAILED : TaskStatus.SUCCESS;
    }

    @Override
    public void stop(TaskContext c) {
        if (pass != null) {
            pass.stop(c);
        }
    }

    @Override
    public String describe() {
        return "farming within " + radius + " blocks of " + center.getX() + " " + center.getY() + " " + center.getZ();
    }

    @Override
    public java.util.List<DebugTarget> debugTargets() {
        return pass == null ? java.util.List.of() : pass.debugTargets();
    }

    @Override
    public java.util.List<WorkSet> debugWorkSets() {
        return pass == null ? java.util.List.of() : pass.debugWorkSets();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    /** The worked plot: a farm named after its crop, or "mixed" when the pass took whatever grew there. */
    @Override
    public PlaceCandidate placeCandidate() {
        if (dimension.isBlank()) {
            return null;
        }
        String label = crop == null
                ? "mixed"
                : PlaceNames.shortItem(BuiltInRegistries.ITEM.getKey(crop).getPath());
        return new PlaceCandidate(
                PlaceKind.FARM,
                label,
                "",
                dimension,
                new Pos(center.getX(), center.getY(), center.getZ()),
                Math.clamp(radius, 1, Place.RADIUS_MAX),
                PlaceSource.FARM);
    }
}
