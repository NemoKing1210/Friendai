package dev.friendai.features.creative.minecraft.task;

import dev.friendai.features.creative.minecraft.CreativeItemCapability;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import net.minecraft.world.item.ItemStack;

/** Delivers one bounded stack of a registered item's default instance per task tick. */
public final class CreateItemsTask implements Task {
    public enum Destination {
        SELF,
        OWNER
    }

    public static final double OWNER_DELIVERY_RADIUS = CreativeItemCapability.OWNER_DELIVERY_RADIUS;

    private final ItemStack prototype;
    private final long requested;
    private final Destination destination;
    private long delivered;
    private String summary = "";

    public CreateItemsTask(ItemStack prototype, long requested, Destination destination) {
        if (prototype == null || prototype.isEmpty()) {
            throw new IllegalArgumentException("item must be a registered non-air item");
        }
        if (requested < 1) {
            throw new IllegalArgumentException("count must be greater than zero");
        }
        if (destination == null) {
            throw new IllegalArgumentException("destination is required");
        }
        this.prototype = prototype.copyWithCount(1);
        this.requested = requested;
        this.destination = destination;
    }

    @Override
    public String type() {
        return "create_item";
    }

    @Override
    public void start(TaskContext context) {}

    @Override
    public TaskStatus tick(TaskContext context) {
        if (delivered >= requested) {
            summary = result("complete");
            return TaskStatus.SUCCESS;
        }
        CreativeItemCapability.Destination target = switch (destination) {
            case SELF -> CreativeItemCapability.Destination.COMPANION;
            case OWNER -> CreativeItemCapability.Destination.OWNER;
        };
        CreativeItemCapability.Batch batch =
                CreativeItemCapability.deliverOneBatch(context, prototype, requested - delivered, target);
        delivered += batch.inserted();
        if (!batch.succeeded()) {
            summary = result(CreativeItemCapability.failureText(batch.failure(), target));
            return TaskStatus.FAILED;
        }
        if (delivered == requested) {
            summary = result("complete");
            return TaskStatus.SUCCESS;
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext context) {}

    @Override
    public String describe() {
        return "creating " + requested + " " + itemName() + " for "
                + (destination == Destination.SELF ? "my inventory" : "the owner's inventory");
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    public long delivered() {
        return delivered;
    }

    public long remaining() {
        return requested - delivered;
    }

    private String itemName() {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(prototype.getItem())
                .toString();
    }

    private String result(String detail) {
        return CreativeItemCapability.resultSummary(prototype, delivered, requested, detail);
    }
}
