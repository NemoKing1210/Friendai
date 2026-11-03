package dev.friendai.features.inventory.minecraft.task;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.navigation.minecraft.nav.MoveHelper;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Walks up to the owner and drops items toward them. Gives what it has if it has fewer than asked. */
public final class GiveItemsTask implements Task {
    private static final double GIVE_DISTANCE = 2.5;
    private static final EquipmentSlot[] HANDS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

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
        mover.reset(c);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        var companion = c.companion();
        int have = InventoryOps.count(companion.inventory(), matcher) + held(companion);
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
        MoveHelper.State state = close ? MoveHelper.State.ARRIVED : mover.tick(c, owner.position());
        if (state == MoveHelper.State.STUCK) {
            summary = "can't reach the owner";
            return TaskStatus.FAILED;
        }
        if (state != MoveHelper.State.ARRIVED && !close) {
            return TaskStatus.RUNNING;
        }
        companion.getNavigation().stop();
        int wanted = Math.min(count, have);
        List<ItemStack> taken = new ArrayList<>(InventoryOps.remove(companion.inventory(), matcher, wanted));
        int remaining = wanted - taken.stream().mapToInt(ItemStack::getCount).sum();
        for (EquipmentSlot slot : HANDS) {
            if (remaining <= 0) break;
            ItemStack stack = companion.getItemBySlot(slot);
            if (!matcher.test(stack)) continue;
            int amount = Math.min(remaining, stack.getCount());
            ItemStack copy = stack.copyWithCount(amount);
            stack.shrink(amount);
            companion.setItemSlot(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            taken.add(copy);
            remaining -= amount;
        }
        int gave = 0;
        for (ItemStack stack : taken) {
            drop(c, owner, stack);
            gave += stack.getCount();
        }
        summary = "gave " + gave + " " + itemLabel();
        return TaskStatus.SUCCESS;
    }

    private int held(CompanionEntity companion) {
        int total = 0;
        for (EquipmentSlot slot : HANDS) {
            ItemStack stack = companion.getItemBySlot(slot);
            if (matcher.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void drop(TaskContext c, ServerPlayer owner, ItemStack stack) {
        var companion = c.companion();
        ItemEntity entity =
                new ItemEntity(c.level(), companion.getX(), companion.getEyeY() - 0.3, companion.getZ(), stack);
        Vec3 toOwner = owner.position()
                .add(0, 1.0, 0)
                .subtract(entity.position())
                .normalize()
                .scale(0.3);
        entity.setDeltaMovement(toOwner);
        entity.setPickUpDelay(10);
        c.level().addFreshEntity(entity);
    }

    @Override
    public void stop(TaskContext c) {
        mover.stop(c);
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
