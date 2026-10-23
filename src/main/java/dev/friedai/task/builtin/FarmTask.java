package dev.friedai.task.builtin;

import dev.friedai.task.Task;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.farming.FarmPass;
import dev.friedai.task.farming.FarmReport;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/** One pass of harvesting, replanting, sowing and (optionally) tilling around a point. */
public final class FarmTask implements Task {
    private final BlockPos center;
    private final int radius;
    private final @Nullable Item crop;
    private final boolean till;
    private FarmPass pass;
    private String summary = "";

    public FarmTask(BlockPos center, int radius, @Nullable Item crop, boolean till) {
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
        pass = new FarmPass(center, radius, crop, till);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (pass == null || !pass.tick(c)) {
            return TaskStatus.RUNNING;
        }
        FarmReport report = pass.report();
        summary = report.text(radius);
        boolean nothing = report.harvested().isEmpty()
                && report.replanted() == 0 && report.planted() == 0 && report.tilled() == 0;
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
    public String resultSummary() {
        return summary;
    }
}
