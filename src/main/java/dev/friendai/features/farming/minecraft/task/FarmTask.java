package dev.friendai.features.farming.minecraft.task;

import dev.friendai.debug.DebugTarget;
import dev.friendai.debug.WorkSet;
import dev.friendai.features.farming.FarmReport;
import dev.friendai.features.farming.minecraft.FarmPass;
import dev.friendai.features.farming.minecraft.FarmWorkHint;
import dev.friendai.features.places.PlaceCandidate;
import dev.friendai.foundation.places.Place;
import dev.friendai.foundation.places.PlaceKind;
import dev.friendai.foundation.places.PlaceNames;
import dev.friendai.foundation.places.PlaceSource;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/** One pass of harvesting, replanting, sowing and (optionally) tilling around a point. */
public final class FarmTask implements Task {
    private BlockPos center;
    private final int radius;
    private final @Nullable Item crop;
    private final boolean till;
    private final boolean boneMeal;
    /** Whether the pass follows the field: moves to the nearest work when empty, and covers work left past its edge. */
    private boolean movesToNearestWork;

    /** Further passes this task may still run over the rest of the same field. */
    private int passesLeft = EXTRA_PASSES;

    /** What the earlier passes of this task did, or null before the first one ended. */
    private @Nullable FarmReport done;

    /** How many further passes one order may take to follow its field. */
    private static final int EXTRA_PASSES = 3;

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

    /**
     * A pass whose centre was only "around the companion", not a place the player named: when nothing in its radius
     * needs work it moves once to the nearest work within {@value FarmWorkHint#RADIUS} blocks and farms there. "Plant
     * wheat" beside a pond a few blocks off used to end with "nothing to do" and a question to the owner.
     */
    public FarmTask movingToNearestWork() {
        this.movesToNearestWork = true;
        return this;
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
        FarmReport report = done == null ? pass.report() : done.plus(pass.report());
        done = report;
        summary = report.text(radius);
        boolean nothing = !report.didWork();
        if (movesToNearestWork && passesLeft > 0) {
            // Follow the field: an empty pass moves to the nearest work, and a pass that did some work covers what is
            // left just past its edge. "Harvest the wheat" with the plot on the rim of the circle took two ears of
            // eight and said nothing ripe was left.
            var work = FarmWorkHint.find(c, center, till);
            if (work.isPresent() && (nothing || beyondThisPass(work.get().at()))) {
                passesLeft--;
                center = work.get().at();
                pass = new FarmPass(center, radius, crop, till, boneMeal);
                return TaskStatus.RUNNING;
            }
        }
        if (nothing) summary += FarmWorkHint.nearest(c, center, radius, till);
        return nothing ? TaskStatus.FAILED : TaskStatus.SUCCESS;
    }

    /** Whether a cell lies outside the disc the pass just covered, so another pass would reach new work there. */
    private boolean beyondThisPass(BlockPos cell) {
        long dx = cell.getX() - center.getX();
        long dz = cell.getZ() - center.getZ();
        return dx * dx + dz * dz > (long) radius * radius;
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
