package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.farming.Animals;
import dev.friendai.task.nav.Approach;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Shears ready sheep or mooshrooms and collects the drops. */
public final class ShearTask implements Task {
    private static final int RADIUS = 16;

    private final EntityType<?> type;
    private final int count;
    private final String id;
    private final Set<UUID> unreachable = new HashSet<>();
    private final Map<String, Integer> gained = new LinkedHashMap<>();

    private Vec3 origin;
    private boolean preflightDone;
    private boolean failed;
    private boolean finished;
    private String summary = "";
    private Animal target;
    private Approach approach;
    private int sheared;

    public ShearTask(EntityType<?> type, int count) {
        this.type = type;
        this.count = count;
        this.id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }

    @Override
    public String type() {
        return "shear";
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
        if (finished) {
            return TaskStatus.SUCCESS;
        }
        if (!preflightDone) {
            preflightDone = true;
            if (!hasShears(c.companion())) {
                summary = "I have no minecraft:shears";
                failed = true;
                return TaskStatus.FAILED;
            }
        }
        if (sheared >= count) {
            return finish();
        }
        if (target != null && (!target.isAlive() || target.isRemoved())) {
            target = null;
            approach = null;
        }
        if (target == null) {
            target = nextReady(c);
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
        ItemStack shears = takeShears(c.companion());
        if (shears.isEmpty()) {
            target = null;
            approach = null;
            return finish();
        }
        Map<Item, Integer> before = itemCounts(c.companion());
        InteractionResult result = c.companion().hands().interact(target, shears);
        c.companion().collectNearbyItems(4.0);
        mergeGained(before, itemCounts(c.companion()));
        if (result.consumesAction()) {
            sheared++;
        } else {
            unreachable.add(target.getUUID());
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
        return "shearing " + id;
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private Animal nextReady(TaskContext c) {
        ServerLevel level = c.level();
        for (Animal animal : Animals.nearby(level, c.companion(), origin, RADIUS, candidate -> candidate == type)) {
            if (unreachable.contains(animal.getUUID())) {
                continue;
            }
            if (animal instanceof Shearable shearable && shearable.readyForShearing()) {
                return animal;
            }
        }
        return null;
    }

    private TaskStatus finish() {
        finished = true;
        if (sheared == 0) {
            summary = "no " + id + " ready to shear nearby";
            return TaskStatus.FAILED;
        }
        StringBuilder text = new StringBuilder("sheared ").append(sheared).append(' ').append(id);
        if (!gained.isEmpty()) {
            text.append(" (got ");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : gained.entrySet()) {
                if (!first) {
                    text.append(", ");
                }
                text.append(entry.getValue()).append(' ').append(entry.getKey());
                first = false;
            }
            text.append(')');
        }
        summary = text.toString();
        return TaskStatus.SUCCESS;
    }

    private static boolean hasShears(CompanionEntity companion) {
        return !takeShearsCopy(companion).isEmpty();
    }

    private static ItemStack takeShearsCopy(CompanionEntity companion) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.is(Items.SHEARS)) {
                return stack.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack takeShears(CompanionEntity companion) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.is(Items.SHEARS)) {
                companion.inventory().setItem(slot, ItemStack.EMPTY);
                companion.inventory().setChanged();
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static Map<Item, Integer> itemCounts(CompanionEntity companion) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private void mergeGained(Map<Item, Integer> before, Map<Item, Integer> after) {
        for (Map.Entry<Item, Integer> entry : after.entrySet()) {
            int gainedCount = entry.getValue() - before.getOrDefault(entry.getKey(), 0);
            if (gainedCount > 0 && entry.getKey() != Items.SHEARS) {
                gained.merge(BuiltInRegistries.ITEM.getKey(entry.getKey()).toString(), gainedCount, Integer::sum);
            }
        }
    }
}
