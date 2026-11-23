package dev.friendai.features.cooking.minecraft;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.crafting.minecraft.Stations;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The companion's loaded furnaces. The watcher posts one notice per job when its furnace finishes or runs out
 * of fuel; a notified job stays until its result is collected.
 *
 * <p>A job is identified by its dimension and position: two furnaces at the same coordinates in different
 * dimensions are different stations, and neither can replace or clear the other's job.
 */
public final class FurnaceJobs {
    /** The identity of one loaded station: its dimension plus its position. */
    private record Key(String dimension, BlockPos pos) {}

    private final List<FurnaceJob> jobs = new ArrayList<>();
    private final Set<Key> notified = new LinkedHashSet<>();

    private static Key keyOf(FurnaceJob job) {
        return new Key(job.dimension(), job.pos());
    }

    public void add(FurnaceJob job) {
        Key key = keyOf(job);
        jobs.removeIf(existing -> keyOf(existing).equals(key));
        notified.remove(key);
        jobs.add(job);
    }

    public List<FurnaceJob> all() {
        return List.copyOf(jobs);
    }

    /** Replaces the list, e.g. when a replacement companion inherits the furnaces of the dead one. */
    public void restore(List<FurnaceJob> restored) {
        jobs.clear();
        notified.clear();
        if (restored == null) {
            return;
        }
        for (FurnaceJob job : restored) {
            if (job != null) {
                jobs.add(job);
            }
        }
    }

    /** Forgets the job for one station; a job at the same coordinates in another dimension stays. */
    public void remove(String dimension, BlockPos pos) {
        Key key = new Key(dimension, pos);
        jobs.removeIf(job -> keyOf(job).equals(key));
        notified.remove(key);
    }

    /** Forgets every job; returns how many there were. */
    public int clear() {
        int count = jobs.size();
        jobs.clear();
        notified.clear();
        return count;
    }

    /** Called every 20 ticks on the server thread from {@link CompanionEntity#tick()}. */
    public void tick(CompanionEntity companion, Consumer<String> notice) {
        if (!(companion.level() instanceof ServerLevel level)) {
            return;
        }
        String dimension = level.dimension().identifier().toString();
        for (FurnaceJob job : List.copyOf(jobs)) {
            BlockPos pos = job.pos();
            if (!dimension.equals(job.dimension()) || !level.hasChunkAt(pos)) {
                // Another dimension or an unloaded chunk says nothing about the furnace: keep watching it.
                continue;
            }
            if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
                drop(job, notice, "is gone; the job for " + job.output() + " was dropped");
                continue;
            }
            // The station at these coordinates today may be a different kind, or a stranger's station.
            if (!level.getBlockState(pos).is(Stations.block(job.station()))) {
                drop(job, notice, "is no longer there; the job for " + job.output() + " was dropped");
                continue;
            }
            Key key = keyOf(job);
            if (notified.contains(key)) {
                continue;
            }
            ItemStack input = furnace.getItem(0);
            ItemStack result = furnace.getItem(2);
            if (input.isEmpty() && !result.isEmpty()) {
                notified.add(key);
                notice.accept("the " + job.station().noun() + " at " + coords(pos) + " finished: "
                        + result.getCount() + " "
                        + BuiltInRegistries.ITEM.getKey(result.getItem()).toString()
                        + " ready (use collect_smelted)");
                continue;
            }
            BlockState state = level.getBlockState(pos);
            boolean lit = state.hasProperty(AbstractFurnaceBlock.LIT) && state.getValue(AbstractFurnaceBlock.LIT);
            if (!input.isEmpty() && !lit && furnace.getItem(1).isEmpty()) {
                notified.add(key);
                notice.accept("the " + job.station().noun() + " at " + coords(pos) + " stopped: out of fuel, "
                        + input.getCount() + " " + job.input() + " left");
            }
        }
    }

    private void drop(FurnaceJob job, Consumer<String> notice, String why) {
        remove(job.dimension(), job.pos());
        notice.accept("the " + job.station().noun() + " at " + coords(job.pos()) + " " + why);
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
