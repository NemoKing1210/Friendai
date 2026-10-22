package dev.friedai.task.builtin;

import dev.friedai.task.Task;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.inventory.InventoryOps;
import dev.friedai.task.inventory.ItemMatcher;
import dev.friedai.task.nav.MoveHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Walks up to the owner and drops items toward them. Gives what it has if it has fewer than asked. */
public final class GiveItemsTask implements Task {
    private static final double GIVE_DISTANCE = 2.5;

    private final ItemMatcher matcher;
    private final int count;
    private final MoveHelper mover = new MoveHelper(1.0, 2.0);
    private String summary = "";

    public GiveItemsTask(ItemMatcher matcher, int count) {
        this.matcher = matcher;
        this.count = count;
    }

    private String itemLabel() {
        return matcher.label();
    }

    @Override
    public String type() {
        return "give_items";
    }

    @Override
    public void start(TaskContext c) {
        mover.reset();
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        var companion = c.companion();
        int have = InventoryOps.count(companion.inventory(), matcher);
        if (have == 0) {
            summary = "I don't have " + itemLabel();
            return TaskStatus.FAILED;
        }
        ServerPlayer owner = c.owner().orElse(null);
        if (owner == null || owner.level() != c.level()) {
            summary = "the owner is not around";
            return TaskStatus.FAILED;
        }
        boolean close = companion.distanceTo(owner) <= GIVE_DISTANCE;
        MoveHelper.State state = close ? MoveHelper.State.ARRIVED : mover.tick(companion, owner.position());
        if (state == MoveHelper.State.STUCK) {
            summary = "can't reach the owner";
            return TaskStatus.FAILED;
        }
        if (state != MoveHelper.State.ARRIVED && !close) {
            return TaskStatus.RUNNING;
        }
        companion.getNavigation().stop();
        int gave = 0;
        for (ItemStack taken : InventoryOps.remove(companion.inventory(), matcher, Math.min(count, have))) {
            drop(c, owner, taken);
            gave += taken.getCount();
        }
        summary = "gave " + gave + " " + itemLabel();
        return TaskStatus.SUCCESS;
    }

    private static void drop(TaskContext c, ServerPlayer owner, ItemStack stack) {
        var companion = c.companion();
        ItemEntity entity = new ItemEntity(c.level(), companion.getX(), companion.getEyeY() - 0.3, companion.getZ(), stack);
        Vec3 toOwner = owner.position().add(0, 1.0, 0).subtract(entity.position()).normalize().scale(0.3);
        entity.setDeltaMovement(toOwner);
        entity.setPickUpDelay(10);
        c.level().addFreshEntity(entity);
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return "giving " + count + " " + itemLabel() + " to the owner";
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
