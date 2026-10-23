package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.farming.Animals;
import dev.friendai.task.nav.Approach;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Tames a wolf, cat or parrot for the companion's owner. */
public final class TameTask implements Task {
    private static final int RADIUS = 16;
    private static final int ATTEMPT_INTERVAL = 20;

    private final String target;
    private final int count;
    private final String id;
    private final String foodPhrase;
    private final List<Item> foods;
    private final Set<UUID> unreachable = new HashSet<>();

    private Vec3 origin;
    private boolean preflightDone;
    private boolean failed;
    private boolean finished;
    private String summary = "";
    private Animal animal;
    private Approach approach;
    private int cooldown;
    private int tamed;

    public TameTask(String target, int count) {
        this.target = target;
        this.count = count;
        this.foods = switch (target) {
            case "wolf" -> List.of(Items.BONE);
            case "cat" -> List.of(Items.COD, Items.SALMON);
            case "parrot" -> List.of(Items.WHEAT_SEEDS, Items.MELON_SEEDS, Items.PUMPKIN_SEEDS, Items.BEETROOT_SEEDS);
            default -> throw new IllegalArgumentException("unknown tame target: " + target);
        };
        this.foodPhrase = switch (target) {
            case "wolf" -> "bone";
            case "cat" -> "raw cod or salmon";
            default -> "wheat, melon, pumpkin or beetroot seeds";
        };
        EntityType<?> type = switch (target) {
            case "wolf" -> EntityTypes.WOLF;
            case "cat" -> EntityTypes.CAT;
            case "parrot" -> EntityTypes.PARROT;
            default -> throw new IllegalArgumentException("unknown tame target: " + target);
        };
        this.id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }

    @Override
    public String type() {
        return "tame_animal";
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
            if (findFood(c.companion()) == null) {
                summary = "I have no " + foodPhrase + " to tame a " + target;
                failed = true;
                return TaskStatus.FAILED;
            }
        }
        if (tamed >= count) {
            return finish(c);
        }
        if (animal != null && (!animal.isAlive() || animal.isRemoved() || isTame(animal))) {
            if (isTame(animal)) {
                tameOwner(c, animal);
                tamed++;
            }
            animal = null;
            approach = null;
        }
        if (animal == null) {
            animal = nextUntamed(c);
            if (animal == null) {
                return tamed > 0 ? finish(c) : fail("no untamed " + target + " nearby");
            }
            approach = null;
            cooldown = 0;
        }
        if (approach == null) {
            approach = new Approach(animal.blockPosition());
        }
        Approach.State state = approach.tick(c.companion());
        if (state == Approach.State.MOVING) {
            return TaskStatus.RUNNING;
        }
        if (state == Approach.State.UNREACHABLE) {
            unreachable.add(animal.getUUID());
            animal = null;
            approach = null;
            return TaskStatus.RUNNING;
        }
        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }
        Item food = findFood(c.companion());
        if (food == null) {
            return fail("ran out of " + foodPhrase + " before the " + target + " trusted me (tamed " + tamed + ")");
        }
        ItemStack one = takeOne(c.companion(), food);
        if (one.isEmpty()) {
            return fail("ran out of " + foodPhrase + " before the " + target + " trusted me (tamed " + tamed + ")");
        }
        c.companion().hands().interact(animal, one);
        cooldown = ATTEMPT_INTERVAL;
        if (isTame(animal)) {
            tameOwner(c, animal);
            tamed++;
            animal = null;
            approach = null;
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return "taming a " + target + " for the owner";
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private Animal nextUntamed(TaskContext c) {
        ServerLevel level = c.level();
        for (Animal candidate : Animals.nearby(level, c.companion(), origin, RADIUS,
                candidateType -> candidateType == typeOf(target))) {
            if (unreachable.contains(candidate.getUUID()) || candidate.isBaby() || isTame(candidate)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private static EntityType<?> typeOf(String target) {
        return switch (target) {
            case "wolf" -> EntityTypes.WOLF;
            case "cat" -> EntityTypes.CAT;
            default -> EntityTypes.PARROT;
        };
    }

    private TaskStatus finish(TaskContext c) {
        finished = true;
        summary = "tamed " + tamed + " " + id + " for " + ownerName(c);
        return TaskStatus.SUCCESS;
    }

    private TaskStatus fail(String text) {
        summary = text;
        failed = true;
        return TaskStatus.FAILED;
    }

    private static boolean isTame(Animal animal) {
        return animal instanceof TamableAnimal tamable && tamable.isTame();
    }

    private static void tameOwner(TaskContext c, Animal animal) {
        if (!(animal instanceof TamableAnimal tamable) || !tamable.isTame()) {
            return;
        }
        UUID owner = c.companion().ownerUuid();
        if (owner == null) {
            return;
        }
        UUID hands = c.companion().hands().player().getUUID();
        EntityReference<?> reference = tamable.getOwnerReference();
        if (reference != null && hands.equals(reference.getUUID())) {
            tamable.setOwnerReference(EntityReference.of(owner));
        }
    }

    private static String ownerName(TaskContext c) {
        return c.companion().owner().map(owner -> owner.getName().getString())
                .orElseGet(() -> c.companion().ownerUuid() == null ? "nobody" : c.companion().ownerUuid().toString());
    }

    private Item findFood(CompanionEntity companion) {
        for (Item food : foods) {
            for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
                if (companion.inventory().getItem(slot).is(food)) {
                    return food;
                }
            }
        }
        return null;
    }

    private static ItemStack takeOne(CompanionEntity companion, Item item) {
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
