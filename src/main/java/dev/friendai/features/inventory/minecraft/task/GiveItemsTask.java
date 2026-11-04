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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Walks up to the owner and drops items toward them. Gives what it has if it has fewer than asked. */
public final class GiveItemsTask implements Task {
    private static final double GIVE_DISTANCE = 2.5;
    private static final EquipmentSlot[] HANDS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
    /** How far away a stack the companion put down itself may lie and still count as carried. */
    private static final double OWN_RADIUS = 4.0;
    /** At most this many items are taken off the ground in one delivery. */
    private static final int MAX_ABSORB = 64;

    private final ItemMatcher matcher;
    private final int count;
    private final MoveHelper mover = new MoveHelper(1.0, 2.0);
    private String summary = "";
    /** Whether the ground around the companion has already been searched since it reached the owner. */
    private boolean searchedGround;

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
        searchedGround = false;
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        var companion = c.companion();
        int pickedUp = 0;
        int have = carried(companion);
        if (have == 0) {
            // A swapped tool lands at the companion's feet when the bag is full: what it put down is still its own.
            pickedUp = absorbNearby(companion);
            have = carried(companion);
        }
        if (have == 0) {
            summary = "I don't have " + itemLabel();
            emitEvidence(c, 0, 0, 0, 0, pickedUp, 0);
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
        if (!searchedGround) {
            // Once at the owner: a stack that fell on the way is picked up here rather than reported missing.
            searchedGround = true;
            if (carried(companion) < count) {
                pickedUp += absorbNearby(companion);
            }
        }
        int bag = InventoryOps.count(companion.inventory(), matcher);
        int inMain = countOf(companion.getMainHandItem());
        int inOff = countOf(companion.getOffhandItem());
        int available = bag + inMain + inOff;
        int wanted = Math.min(count, available);
        List<ItemStack> taken = new ArrayList<>(InventoryOps.remove(companion.inventory(), matcher, wanted));
        int remaining = wanted - taken.stream().mapToInt(ItemStack::getCount).sum();
        for (EquipmentSlot slot : HANDS) {
            if (remaining <= 0) break;
            ItemStack stack = hand(slot, companion);
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
            ItemEntity drop = drop(c, owner, stack);
            gave += stack.getCount();
            // The companion's own hand-over is evidence while the drop exists; the owner's pickup is confirmation.
            companion.recordDelivery(new dev.friendai.features.inventory.DeliveredLedger.Entry(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(),
                    c.level().getGameTime(),
                    drop.getUUID()));
        }
        summary = "gave " + gave + " " + itemLabel();
        emitEvidence(c, available, bag, inMain, inOff, pickedUp, gave);
        return TaskStatus.SUCCESS;
    }

    /** Everything the companion could hand over right now: the bag plus both hands. */
    private int carried(CompanionEntity companion) {
        return InventoryOps.count(companion.inventory(), matcher)
                + countOf(companion.getMainHandItem())
                + countOf(companion.getOffhandItem());
    }

    private int countOf(ItemStack stack) {
        return !stack.isEmpty() && matcher.test(stack) ? stack.getCount() : 0;
    }

    /** The hand's stack, read the way the rest of the codebase reads a hand rather than through the slot map. */
    private static ItemStack hand(EquipmentSlot slot, CompanionEntity companion) {
        return slot == EquipmentSlot.MAINHAND ? companion.getMainHandItem() : companion.getOffhandItem();
    }

    /**
     * Takes matching stacks lying within {@link #OWN_RADIUS} blocks into the bag: a tool the companion swapped out with
     * a full bag was dropped at its feet, and a delivery owns what it put down. Bounded by {@link #MAX_ABSORB}.
     */
    private int absorbNearby(CompanionEntity companion) {
        List<ItemEntity> lying = companion
                .level()
                .getEntitiesOfClass(
                        ItemEntity.class,
                        companion.getBoundingBox().inflate(OWN_RADIUS),
                        entity -> entity.isAlive() && !entity.isRemoved() && matcher.test(entity.getItem()));
        int absorbed = 0;
        for (ItemEntity entity : lying) {
            if (absorbed >= MAX_ABSORB) {
                break;
            }
            ItemStack stack = entity.getItem();
            int amount = Math.min(stack.getCount(), MAX_ABSORB - absorbed);
            ItemStack moving = stack.copyWithCount(amount);
            stack.shrink(amount);
            if (stack.isEmpty()) {
                entity.discard();
            } else {
                entity.setItem(stack);
            }
            InventoryOps.insertOrDrop(companion, moving);
            absorbed += amount;
        }
        return absorbed;
    }

    /** Bounded, already-observed evidence; a delivered item is not proof the owner picked it up. */
    private void emitEvidence(
            TaskContext c, int availableBefore, int bag, int inMain, int inOff, int pickedUp, int gave) {
        var companion = c.companion();
        java.util.Map<String, String> facts = new java.util.LinkedHashMap<>();
        facts.put("requested", Integer.toString(count));
        facts.put("available", Integer.toString(availableBefore));
        facts.put("inventoryCount", Integer.toString(bag));
        facts.put("heldMain", Integer.toString(inMain));
        facts.put("heldOff", Integer.toString(inOff));
        facts.put("pickedUp", Integer.toString(pickedUp));
        facts.put("removed", Integer.toString(gave));
        facts.put("dropped", Integer.toString(gave));
        facts.put("carriedAfter", Integer.toString(InventoryOps.count(companion.inventory(), matcher)));
        facts.put("ownerReceipt", "unknown");
        c.decision(dev.friendai.foundation.diagnostics.LogEvents.INVENTORY_DELTA, "give_completed", facts);
    }

    private static ItemEntity drop(TaskContext c, ServerPlayer owner, ItemStack stack) {
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
        return entity;
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
