package dev.friedai.task.builtin;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.Task;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.farming.Animals;
import dev.friedai.task.nav.Approach;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Feeds pairs of adult animals of one type so they breed. */
public final class BreedTask implements Task {
    private static final int RADIUS = 16;
    private static final int BREED_WAIT = 200;

    private enum Feed { MOVING, FED, GONE, NO_FOOD }

    private final EntityType<?> type;
    private final int pairs;
    private final String id;

    private Vec3 origin;
    private boolean preflightDone;
    private boolean failed;
    private boolean finished;
    private String summary = "";
    private Animal first;
    private Animal second;
    private UUID firstId;
    private boolean firstFed;
    private Approach approach;
    private BlockPos approachPos;
    private int fedPairs;
    private int initialBabies;
    private int baselineBabies;
    private int waitTicks;
    private boolean waiting;

    public BreedTask(EntityType<?> type, int pairs) {
        this.type = type;
        this.pairs = pairs;
        this.id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }

    @Override
    public String type() {
        return "breed_animals";
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
            initialBabies = babies(c);
            if (!preflight(c)) {
                return TaskStatus.FAILED;
            }
        }
        if (waiting) {
            if (babies(c) > baselineBabies || --waitTicks <= 0) {
                waiting = false;
            }
            return TaskStatus.RUNNING;
        }
        if (fedPairs >= pairs) {
            return finish(c);
        }
        if (!firstFed) {
            first = nextReady(c, null);
            if (first == null) {
                return fedPairs > 0 ? finish(c) : fail("not enough adult " + id + " ready to breed nearby");
            }
            baselineBabies = babies(c);
            approach = null;
            Feed feed = feed(c, first);
            if (feed == Feed.MOVING) {
                return TaskStatus.RUNNING;
            }
            if (feed == Feed.NO_FOOD) {
                return fedPairs > 0 ? finish(c) : fail(noFood());
            }
            if (feed == Feed.GONE) {
                first = null;
                approach = null;
                return TaskStatus.RUNNING;
            }
            firstId = first.getUUID();
            first = null;
            approach = null;
            firstFed = true;
        }

        if (second == null) {
            second = nextReady(c, firstId);
            if (second == null) {
                firstFed = false;
                return fedPairs > 0 ? finish(c) : fail("not enough adult " + id + " ready to breed nearby");
            }
        }
        Feed feed = feed(c, second);
        if (feed == Feed.MOVING) {
            return TaskStatus.RUNNING;
        }
        if (feed == Feed.NO_FOOD) {
            return fedPairs > 0 ? finish(c) : fail(noFood());
        }
        if (feed == Feed.GONE) {
            second = null;
            approach = null;
            firstFed = false;
            return TaskStatus.RUNNING;
        }
        second = null;
        approach = null;
        firstFed = false;
        fedPairs++;
        baselineBabies = babies(c);
        waitTicks = BREED_WAIT;
        waiting = true;
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return "breeding " + id + " nearby";
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private boolean preflight(TaskContext c) {
        ServerLevel level = c.level();
        int adults = Animals.adults(level, origin, RADIUS, type);
        if (adults >= Animals.BREED_LIMIT) {
            summary = "there are already " + adults + " " + id + " here";
            failed = true;
            return false;
        }
        if (adults < 2) {
            summary = "not enough adult " + id + " ready to breed nearby";
            failed = true;
            return false;
        }
        List<Animal> nearby = Animals.nearby(level, c.companion(), origin, RADIUS, candidate -> candidate == type);
        if (nearby.isEmpty()) {
            summary = "not enough adult " + id + " ready to breed nearby";
            failed = true;
            return false;
        }
        boolean anyFood = nearby.stream().anyMatch(animal -> findFood(c.companion(), animal) != null);
        if (!anyFood) {
            summary = noFood();
            failed = true;
            return false;
        }
        return true;
    }

    private Animal nextReady(TaskContext c, UUID exclude) {
        for (Animal animal : Animals.nearby(c.level(), c.companion(), origin, RADIUS, candidate -> candidate == type)) {
            if (animal.isBaby() || !animal.canFallInLove()) {
                continue;
            }
            if (exclude != null && exclude.equals(animal.getUUID())) {
                continue;
            }
            return animal;
        }
        return null;
    }

    private Feed feed(TaskContext c, Animal animal) {
        if (!animal.isAlive() || animal.isRemoved()) {
            return Feed.GONE;
        }
        if (approach == null || (!animal.blockPosition().equals(approachPos)
                && c.companion().distanceToSqr(animal) > Approach.REACH * Approach.REACH)) {
            approachPos = animal.blockPosition().immutable();
            approach = new Approach(approachPos);
        }
        switch (approach.tick(c.companion())) {
            case MOVING -> {
                return Feed.MOVING;
            }
            case UNREACHABLE -> {
                return Feed.GONE;
            }
            case IN_REACH -> {
            }
        }
        Item food = findFood(c.companion(), animal);
        if (food == null) {
            return Feed.NO_FOOD;
        }
        ItemStack one = takeOne(c.companion(), food);
        if (one.isEmpty()) {
            return Feed.NO_FOOD;
        }
        InteractionResult result = c.companion().hands().interact(animal, one);
        return result.consumesAction() ? Feed.FED : Feed.MOVING;
    }

    private static Item findFood(CompanionEntity companion, Animal animal) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (!stack.isEmpty() && animal.isFood(stack)) {
                return stack.getItem();
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

    private int babies(TaskContext c) {
        return (int) Animals.nearby(c.level(), c.companion(), origin, RADIUS, candidate -> candidate == type)
                .stream().filter(Animal::isBaby).count();
    }

    private TaskStatus finish(TaskContext c) {
        finished = true;
        summary = "fed " + fedPairs + " pairs of " + id + "; " + Math.max(0, babies(c) - initialBabies) + " babies so far";
        return TaskStatus.SUCCESS;
    }

    private String noFood() {
        return "I have no food that " + id + " eats";
    }

    private TaskStatus fail(String text) {
        summary = text;
        failed = true;
        return TaskStatus.FAILED;
    }
}
