package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.debug.DebugTarget;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.farming.Crops;
import dev.friendai.task.farming.FarmPass;
import dev.friendai.task.farming.FarmReport;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.nav.MoveHelper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Stays at a farm, harvests every period, and stores the harvest in a nearby chest until stopped. */
public final class TendFarmTask implements Task {
    private static final int DEFAULT_PERIOD = 1200;
    private static final int STORE_RADIUS = 16;
    private static final double STAY_DISTANCE = 3.0;

    private final BlockPos center;
    private final int radius;
    private final int period;

    private final MoveHelper mover = new MoveHelper(1.0, 1.0);
    private FarmPass pass;
    private int waitTicks;
    private FarmReport lastReport;
    private String summary = "";
    private boolean fullNotified;
    private boolean noChestNotified;
    private boolean seedsNotified;

    public TendFarmTask(BlockPos center, int radius) {
        this(center, radius, DEFAULT_PERIOD);
    }

    /** @param period ticks between passes; tests use a short period */
    public TendFarmTask(BlockPos center, int radius, int period) {
        this.center = center;
        this.radius = radius;
        this.period = period;
    }

    @Override
    public String type() {
        return "tend_farm";
    }

    @Override
    public void start(TaskContext c) {
        // The plot comes from the constructor; tending has nothing else to read when it starts.
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (pass != null) {
            if (pass.tick(c)) {
                lastReport = pass.report();
                pass = null;
                waitTicks = period;
                store(c);
                updateSeedNotice(c);
            }
            return TaskStatus.RUNNING;
        }
        if (waitTicks > 0) {
            waitTicks--;
            stayNear(c);
            return TaskStatus.RUNNING;
        }
        pass = new FarmPass(center, radius, null, false);
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        mover.stop(c);
        if (pass != null) {
            pass.stop(c);
        }
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return "tending the farm at " + coords() + " (radius " + radius + ")";
    }

    @Override
    public java.util.List<DebugTarget> debugTargets() {
        return pass == null ? java.util.List.of() : pass.debugTargets();
    }

    @Override
    public String resultSummary() {
        return summary.isEmpty() ? "stopped tending the farm at " + coords() : summary;
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public String saveData() {
        return "tend_farm:" + coords().replace(' ', ',') + "," + radius;
    }

    private void stayNear(TaskContext c) {
        Vec3 at = Vec3.atCenterOf(center);
        if (c.companion().position().distanceToSqr(at) > STAY_DISTANCE * STAY_DISTANCE) {
            mover.tick(c.companion(), at);
        } else {
            c.companion().getNavigation().stop();
        }
    }

    private void store(TaskContext c) {
        CompanionEntity companion = c.companion();
        Map<Item, Integer> totals = farmOutputs(companion);
        Optional<BlockPos> chestPos = ContainerAccess.nearest(companion, STORE_RADIUS);
        Optional<Container> chest = chestPos.flatMap(pos -> ContainerAccess.at(c.level(), pos));
        if (chest.isEmpty()) {
            if (!totals.isEmpty()) {
                notice(c, noChestNotified, "no chest near the farm at " + coords() + "; keeping the harvest");
                noChestNotified = true;
            } else {
                noChestNotified = false;
            }
            return;
        }
        noChestNotified = false;
        if (totals.isEmpty()) {
            fullNotified = false;
            return;
        }
        BlockPos selectedChest = chestPos.orElseThrow();
        if (!companion.hands().canInteract(selectedChest)) return;
        chest.get().startOpen(companion.hands().player());

        int attempted = 0;
        int moved = 0;
        for (Map.Entry<Item, Integer> entry : totals.entrySet()) {
            Item item = entry.getKey();
            int keep = Crops.isPlantableSeed(item) ? new ItemStack(item).getMaxStackSize() : 0;
            int storeable = Math.max(0, entry.getValue() - keep);
            if (storeable == 0) {
                continue;
            }
            attempted += storeable;
            for (int slot = 0; slot < companion.inventory().getContainerSize() && storeable > 0; slot++) {
                ItemStack stack = companion.inventory().getItem(slot);
                if (!stack.is(item) || stack.isEmpty()) {
                    continue;
                }
                int take = Math.min(storeable, stack.getCount());
                ItemStack leftover = ContainerAccess.insert(chest.get(), stack.copyWithCount(take));
                int inserted = take - leftover.getCount();
                if (inserted > 0) {
                    stack.shrink(inserted);
                    if (stack.isEmpty()) {
                        companion.inventory().setItem(slot, ItemStack.EMPTY);
                    }
                    moved += inserted;
                    storeable -= inserted;
                }
            }
        }
        companion.inventory().setChanged();
        chest.get().setChanged();
        ContainerAccess.recordContents(c.level(), companion, selectedChest, chest.get());
        chest.get().stopOpen(companion.hands().player());
        if (attempted > 0 && moved == 0) {
            notice(c, fullNotified, "the chests near the farm at " + coords() + " are full");
            fullNotified = true;
        } else if (moved > 0) {
            fullNotified = false;
            summary = "harvested " + moved + " items and stored them near the farm at " + coords();
        }
    }

    private static Map<Item, Integer> farmOutputs(CompanionEntity companion) {
        Map<Item, Integer> totals = new LinkedHashMap<>();
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (!stack.isEmpty() && isFarmOutput(stack.getItem())) {
                totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return totals;
    }

    private static boolean isFarmOutput(Item item) {
        return Crops.isPlantableSeed(item)
                || item == Items.WHEAT
                || item == Items.MELON_SLICE
                || item == Items.PUMPKIN
                || item == Items.SUGAR_CANE
                || item == Items.SWEET_BERRIES
                || item == Items.BEETROOT;
    }

    private void updateSeedNotice(TaskContext c) {
        boolean outOfSeeds = lastReport != null && lastReport.noSeeds() > 0;
        if (outOfSeeds) {
            StringBuilder need = new StringBuilder();
            lastReport.seedShortfall().forEach((item, count) -> need.append(need.isEmpty() ? "" : ", ")
                    .append("need ").append(count).append(' ').append(item));
            notice(c, seedsNotified, "I'm out of seeds for the farm at " + coords()
                    + (need.isEmpty() ? "" : " (" + need + ")"));
            seedsNotified = true;
        } else {
            seedsNotified = false;
        }
    }

    private void notice(TaskContext c, boolean alreadySent, String text) {
        if (!alreadySent) {
            c.companion().taskRunner().notice(text);
        }
    }

    private String coords() {
        return center.getX() + " " + center.getY() + " " + center.getZ();
    }
}
