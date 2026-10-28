package dev.friendai.task.cooking;

import dev.friendai.entity.CompanionEntity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The companion's loaded furnaces. The watcher posts one notice per job when its furnace finishes or runs out
 * of fuel; a notified job stays until its result is collected.
 */
public final class FurnaceJobs {
    private final List<FurnaceJob> jobs = new ArrayList<>();
    private final Set<BlockPos> notified = new LinkedHashSet<>();

    public void add(FurnaceJob job) {
        jobs.removeIf(existing -> existing.pos().equals(job.pos()));
        notified.remove(job.pos());
        jobs.add(job);
    }

    public List<FurnaceJob> all() {
        return List.copyOf(jobs);
    }

    public void remove(BlockPos pos) {
        jobs.removeIf(job -> job.pos().equals(pos));
        notified.remove(pos);
    }

    /** Forgets every job; returns how many there were. */
    public int clear() {
        int count = jobs.size();
        jobs.clear();
        notified.clear();
        return count;
    }

    /** Called every 20 ticks on the server thread from {@link CompanionEntity#tick()}. */
    public void tick(CompanionEntity companion) {
        if (!(companion.level() instanceof ServerLevel level)) {
            return;
        }
        for (FurnaceJob job : List.copyOf(jobs)) {
            BlockPos pos = job.pos();
            if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
                drop(companion, job);
                continue;
            }
            if (notified.contains(pos)) {
                continue;
            }
            ItemStack input = furnace.getItem(0);
            ItemStack result = furnace.getItem(2);
            if (input.isEmpty() && !result.isEmpty()) {
                notified.add(pos);
                companion
                        .taskRunner()
                        .notice("the " + job.station().noun() + " at " + coords(pos) + " finished: " + result.getCount()
                                + " " + job.output() + " ready (use collect_smelted)");
                continue;
            }
            BlockState state = level.getBlockState(pos);
            boolean lit = state.hasProperty(AbstractFurnaceBlock.LIT) && state.getValue(AbstractFurnaceBlock.LIT);
            if (!input.isEmpty() && !lit && furnace.getItem(1).isEmpty()) {
                notified.add(pos);
                companion
                        .taskRunner()
                        .notice("the " + job.station().noun() + " at " + coords(pos) + " stopped: " + "out of fuel, "
                                + input.getCount() + " " + job.input() + " left");
            }
        }
    }

    private void drop(CompanionEntity companion, FurnaceJob job) {
        jobs.remove(job);
        notified.remove(job.pos());
        companion
                .taskRunner()
                .notice("the " + job.station().noun() + " at " + coords(job.pos()) + " is gone; " + "the job for "
                        + job.output() + " was dropped");
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
