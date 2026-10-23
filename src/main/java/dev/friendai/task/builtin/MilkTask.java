package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.farming.Animals;
import dev.friendai.task.nav.Approach;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Fills buckets with milk from cows or goats. */
public final class MilkTask implements Task {
    private static final int RADIUS = 16;

    private final int count;
    private final @Nullable EntityType<?> from;
    private final Set<UUID> unreachable = new HashSet<>();

    private Vec3 origin;
    private boolean preflightDone;
    private boolean failed;
    private String summary = "";
    private Animal target;
    private Approach approach;
    private int filled;

    public MilkTask(int count, @Nullable EntityType<?> from) {
        this.count = count;
        this.from = from;
    }

    @Override
    public String type() {
        return "milk";
    }

    @Override
    public void start(TaskContext c) {
        origin = c.companion().position();
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (failed) {
            return TaskStatus.FAILED;
        }
        if (!preflightDone) {
            preflightDone = true;
            if (!hasBucket(c.companion())) {
                summary = "I have no minecraft:bucket";
                failed = true;
                return TaskStatus.FAILED;
            }
        }
        if (filled >= count) {
            return finish();
        }
        if (!hasBucket(c.companion())) {
            return finish();
        }
        if (target != null && (!target.isAlive() || target.isRemoved())) {
            target = null;
            approach = null;
        }
        if (target == null) {
            target = nearest(c);
            if (target == null) {
                return finish();
            }
            approach = null;
        }
        if (approach == null) {
            approach = new Approach(target.blockPosition());
        }
        Approach.State state = approach.tick(c.companion());
        if (state == Approach.State.MOVING) {
            return TaskStatus.RUNNING;
        }
        if (state == Approach.State.UNREACHABLE) {
            unreachable.add(target.getUUID());
            target = null;
            approach = null;
            return TaskStatus.RUNNING;
        }
        ItemStack bucket = takeOne(c.companion(), Items.BUCKET);
        if (bucket.isEmpty()) {
            return finish();
        }
        int before = c.companion().inventory().countItem(Items.MILK_BUCKET);
        InteractionResult result = c.companion().hands().interact(target, bucket);
        if (result.consumesAction() && c.companion().inventory().countItem(Items.MILK_BUCKET) > before) {
            filled++;
        }
        target = null;
        approach = null;
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return "milking " + (from == null ? "cows and goats" : from);
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private Animal nearest(TaskContext c) {
        ServerLevel level = c.level();
        for (Animal animal : Animals.nearby(level, c.companion(), origin, RADIUS, this::matches)) {
            if (!unreachable.contains(animal.getUUID())) {
                return animal;
            }
        }
        return null;
    }

    private boolean matches(EntityType<?> type) {
        return from == null ? type == EntityTypes.COW || type == EntityTypes.GOAT : type == from;
    }

    private TaskStatus finish() {
        if (filled == 0) {
            summary = "no cows or goats nearby";
            return TaskStatus.FAILED;
        }
        summary = "filled " + filled + " minecraft:milk_bucket";
        return TaskStatus.SUCCESS;
    }

    private static boolean hasBucket(CompanionEntity companion) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            if (companion.inventory().getItem(slot).is(Items.BUCKET)) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack takeOne(CompanionEntity companion, net.minecraft.world.item.Item item) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.is(item)) {
                ItemStack one = stack.copyWithCount(1);
                stack.shrink(1);
                if (stack.isEmpty()) {
                    companion.inventory().setItem(slot, ItemStack.EMPTY);
                }
                companion.inventory().setChanged();
                return one;
            }
        }
        return ItemStack.EMPTY;
    }
}
