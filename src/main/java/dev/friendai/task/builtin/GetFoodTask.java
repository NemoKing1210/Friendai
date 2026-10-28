package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.combat.Food;
import dev.friendai.task.combat.Threats;
import dev.friendai.task.cooking.FuelPolicy;
import dev.friendai.task.farming.Crops;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.interact.ContainerSession;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;

/**
 * Gets ready food the cheapest way available: cook what it carries, take it from a chest, bake bread, harvest,
 * hunt (then cook) or fish. Plans one option at a time and re-plans from the inventory after every step, like
 * {@link ObtainTask}. An option that fails twice in a row is dropped for the rest of the task.
 */
public final class GetFoodTask implements Task {
    public static final int MAX_COUNT = 16;
    public static final int DEFAULT_MAX_STEPS = 12;
    public static final int DEFAULT_MAX_TICKS = 12_000;
    /** What the player and the LLM hear when the surroundings offer nothing at all. */
    public static final String NOTHING_AVAILABLE =
            "I can't find any food here: no food in chests, no crops, no animals and no way to fish";

    /**
     * Raw items that cook into safe food. They never count as ready food: while they can be cooked they are
     * an ingredient, not a meal.
     */
    private static final Set<Item> RAW_FOOD = Set.of(
            Items.BEEF,
            Items.PORKCHOP,
            Items.CHICKEN,
            Items.MUTTON,
            Items.RABBIT,
            Items.COD,
            Items.SALMON,
            Items.POTATO);
    /** Animals that are free to hunt: farm animals have no owner, so only leashes protect them. */
    private static final Set<EntityType<?>> HUNTABLE = Set.of(
            EntityTypes.COW,
            EntityTypes.PIG,
            EntityTypes.SHEEP,
            EntityTypes.CHICKEN,
            EntityTypes.RABBIT,
            EntityTypes.COD,
            EntityTypes.SALMON);

    private static final int CONTAINER_RADIUS = 16;
    private static final int CROP_RADIUS = 16;
    private static final int WATER_RADIUS = 16;
    private static final int FARM_RADIUS = 8;
    private static final int HUNT_RADIUS = 24;
    private static final int HUNT_MAX = 3;
    private static final int SMELT_WAIT_TICKS = 1_200;
    private static final int WHEAT_PER_BREAD = 3;
    /** One smelt per raw item shows whether the inventory holds a fuel the companion may burn unasked. */
    private static final int FUEL_CHECK_TICKS = 200;

    /** The six ways to get food, in the order they are tried. */
    private enum Option {
        COOKING("cooking"),
        CHEST("chest"),
        BAKING("baking"),
        HARVESTING("harvesting"),
        HUNTING("hunting"),
        FISHING("fishing");

        private final String shortName;

        Option(String shortName) {
            this.shortName = shortName;
        }
    }

    private final int count;
    private final int maxSteps;
    private final int maxTicks;

    private final Set<Option> skipped = EnumSet.noneOf(Option.class);
    private Task current;
    private Option currentOption;
    private Option lastFailedOption;
    private String summary = "";
    private int ticks;
    private int stepsRun;
    private int readyAtStart;
    private boolean started;

    public GetFoodTask(int count) {
        this(count, DEFAULT_MAX_STEPS, DEFAULT_MAX_TICKS);
    }

    /** The full constructor, for tests with tighter limits. */
    GetFoodTask(int count, int maxSteps, int maxTicks) {
        this.count = Math.max(1, Math.min(MAX_COUNT, count));
        this.maxSteps = Math.max(1, maxSteps);
        this.maxTicks = Math.max(1, maxTicks);
    }

    @Override
    public String type() {
        return "get_food";
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public void start(TaskContext c) {
        current = null;
        currentOption = null;
        lastFailedOption = null;
        summary = "";
        ticks = 0;
        stepsRun = 0;
        readyAtStart = 0;
        started = false;
        skipped.clear();
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (!started) {
            started = true;
            readyAtStart = readyCount(c.companion());
        }
        if (++ticks > maxTicks) {
            return fail("ran out of time; have " + readyCount(c.companion()) + " of " + count + " food");
        }
        if (current != null) {
            TaskStatus status = current.tick(c);
            if (status == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            safeStop(current, c);
            Option option = currentOption;
            current = null;
            currentOption = null;
            if (status == TaskStatus.SUCCESS) {
                lastFailedOption = null;
            } else if (option == lastFailedOption) {
                skipped.add(option);
                lastFailedOption = null;
            } else {
                lastFailedOption = option;
            }
        }
        return startNext(c);
    }

    private TaskStatus startNext(TaskContext c) {
        CompanionEntity companion = c.companion();
        int have = readyCount(companion);
        if (have >= count) {
            return succeed(companion);
        }
        if (stepsRun >= maxSteps) {
            return fail("gave up after " + maxSteps + " steps; have " + have + " of " + count + " food");
        }
        Option option = chooseOption(c);
        if (option == null) {
            return fail(NOTHING_AVAILABLE);
        }
        Task task = subtask(c, option);
        if (task == null) {
            return fail(NOTHING_AVAILABLE);
        }
        stepsRun++;
        currentOption = option;
        current = task;
        current.start(c);
        return TaskStatus.RUNNING;
    }

    private Option chooseOption(TaskContext c) {
        for (Option option : Option.values()) {
            if (!skipped.contains(option) && available(c, option)) {
                return option;
            }
        }
        return null;
    }

    private boolean available(TaskContext c, Option option) {
        CompanionEntity companion = c.companion();
        return switch (option) {
            case COOKING -> hasRawFood(companion) && hasAllowedFuel(c);
            case CHEST -> ContainerAccess.nearest(companion, CONTAINER_RADIUS).isPresent();
            case BAKING -> wheatCount(companion) >= WHEAT_PER_BREAD;
            case HARVESTING -> nearestMatureCrop(c).isPresent();
            case HUNTING -> nearestHuntable(c.level(), companion) != null;
            case FISHING -> hasFishingRod(companion) && hasOpenWater(c.level(), companion.blockPosition());
        };
    }

    private Task subtask(TaskContext c, Option option) {
        CompanionEntity companion = c.companion();
        int remaining = Math.max(1, count - readyCount(companion));
        ServerLevel level = c.level();
        return switch (option) {
            case COOKING -> new CookFoodTask(remaining, true, CraftTask.StationPolicy.AUTO, SMELT_WAIT_TICKS);
            case CHEST -> new TakeFoodFromChestTask(remaining);
            case BAKING -> {
                int loaves = Math.min(remaining, wheatCount(companion) / WHEAT_PER_BREAD);
                yield loaves <= 0 ? null : new CraftTask("minecraft:bread", loaves, CraftTask.StationPolicy.AUTO);
            }
            case HARVESTING ->
                nearestMatureCrop(c)
                        .map(center -> (Task) new FarmTask(center, FARM_RADIUS, null, false))
                        .orElse(null);
            case HUNTING -> {
                LivingEntity target = nearestHuntable(level, companion);
                if (target == null) {
                    yield null;
                }
                EntityType<?> type = target.getType();
                yield new HuntTask(
                        matched -> matched == type, entityId(type), Math.min(remaining, HUNT_MAX), HUNT_RADIUS);
            }
            case FISHING -> new FishTask(remaining);
        };
    }

    private boolean hasRawFood(CompanionEntity companion) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (!stack.isEmpty() && RAW_FOOD.contains(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    /** Whether the inventory holds a fuel the companion may burn without being told to (coal, planks, sticks). */
    private boolean hasAllowedFuel(TaskContext c) {
        CompanionEntity companion = c.companion();
        return !FuelPolicy.pick(companion, c.level(), companion.blockPosition(), FUEL_CHECK_TICKS, null)
                .stacks()
                .isEmpty();
    }

    /** Chest contents are inspected only after the companion has walked to and opened a known container. */
    private static final class TakeFoodFromChestTask implements Task {
        private final int wanted;
        private final ContainerSession session;
        private String summary = "";

        private TakeFoodFromChestTask(int wanted) {
            this.wanted = wanted;
            this.session = new ContainerSession(
                    null,
                    (level, companion) -> ContainerAccess.nearest(companion, CONTAINER_RADIUS),
                    false,
                    () -> "no known storage container within " + CONTAINER_RADIUS + " blocks",
                    position -> "the storage container is no longer there");
        }

        @Override
        public String type() {
            return "take_food";
        }

        @Override
        public void start(TaskContext c) {}

        @Override
        public TaskStatus tick(TaskContext c) {
            return session.tick(c, this::takeFood);
        }

        private TaskStatus takeFood(TaskContext c, BlockPos containerPos, Container container) {
            int bestSlot = -1;
            FoodProperties best = null;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!isReadyFood(c.companion(), stack)) continue;
                FoodProperties food = stack.get(DataComponents.FOOD);
                if (best == null
                        || food.nutrition() > best.nutrition()
                        || food.nutrition() == best.nutrition() && food.saturation() > best.saturation()) {
                    bestSlot = slot;
                    best = food;
                }
            }
            if (bestSlot < 0) {
                summary = "the " + ContainerAccess.shortLabel(c.level(), containerPos) + " at " + coords(containerPos)
                        + " has no ready food";
                return TaskStatus.FAILED;
            }

            ItemStack source = container.getItem(bestSlot);
            ItemStack removed = container.removeItem(bestSlot, Math.min(wanted, source.getCount()));
            int taken = removed.getCount();
            ItemStack leftover = c.companion().inventory().addItem(removed);
            taken -= leftover.getCount();
            if (!leftover.isEmpty()) {
                ItemStack current = container.getItem(bestSlot);
                if (current.isEmpty()) container.setItem(bestSlot, leftover);
                else current.grow(leftover.getCount());
            }
            c.companion().inventory().setChanged();
            summary = taken == 0
                    ? "my inventory is full"
                    : "took " + taken + " ready food from the " + ContainerAccess.shortLabel(c.level(), containerPos)
                            + " at " + coords(containerPos);
            return taken == 0 ? TaskStatus.FAILED : TaskStatus.SUCCESS;
        }

        @Override
        public String describe() {
            return "taking ready food from a container";
        }

        @Override
        public String resultSummary() {
            return summary.isEmpty() ? session.failureSummary() : summary;
        }

        @Override
        public void stop(TaskContext c) {
            session.stop(c);
        }

        @Override
        public List<dev.friendai.debug.DebugTarget> debugTargets() {
            return session.debugTargets();
        }

        private static String coords(BlockPos position) {
            return position.getX() + " " + position.getY() + " " + position.getZ();
        }
    }

    private static Optional<BlockPos> nearestMatureCrop(TaskContext c) {
        var evidence =
                dev.friendai.core.FriendaiServerState.get(c.level().getServer()).resources();
        return evidence.confirmedRecent(c.companion(), CROP_RADIUS).stream()
                .map(observation -> observation.location().pos())
                .map(pos -> new BlockPos(pos.x(), pos.y(), pos.z()))
                .filter(pos -> Crops.harvestable(c.level(), pos).isPresent())
                .min(java.util.Comparator.comparingDouble(c.companion().blockPosition()::distSqr));
    }

    private static LivingEntity nearestHuntable(ServerLevel level, CompanionEntity companion) {
        List<LivingEntity> nearby = level.getEntitiesOfClass(
                LivingEntity.class,
                new AABB(companion.blockPosition()).inflate(HUNT_RADIUS),
                entity -> entity.isAlive()
                        && !entity.isRemoved()
                        && HUNTABLE.contains(entity.getType())
                        && !(entity instanceof Leashable leashable && leashable.isLeashed())
                        && !Threats.isProtected(entity, companion));
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity entity : nearby) {
            if (level.clip(new ClipContext(
                                    companion.getEyePosition(),
                                    entity.getBoundingBox().getCenter(),
                                    ClipContext.Block.COLLIDER,
                                    ClipContext.Fluid.NONE,
                                    companion))
                            .getType()
                    != HitResult.Type.MISS) continue;
            double distance = entity.distanceToSqr(companion);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    private static boolean hasFishingRod(CompanionEntity companion) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            if (companion.inventory().getItem(slot).is(Items.FISHING_ROD)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasOpenWater(ServerLevel level, BlockPos origin) {
        for (int dx = -WATER_RADIUS; dx <= WATER_RADIUS; dx++) {
            for (int dz = -WATER_RADIUS; dz <= WATER_RADIUS; dz++) {
                if (!level.hasChunkAt(origin.getX() + dx, origin.getZ() + dz)) {
                    continue;
                }
                for (int dy = -4; dy <= 4; dy++) {
                    if (openWater(level, origin.offset(dx, dy, dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** A source block with air above and a 2×2 patch of source water around it, as {@link FishTask} wants. */
    private static boolean openWater(ServerLevel level, BlockPos pos) {
        if (!sourceWater(level, pos) || !level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        return sourceWater(level, pos.east())
                        && sourceWater(level, pos.south())
                        && sourceWater(level, pos.east().south())
                || sourceWater(level, pos.west())
                        && sourceWater(level, pos.south())
                        && sourceWater(level, pos.west().south())
                || sourceWater(level, pos.east())
                        && sourceWater(level, pos.north())
                        && sourceWater(level, pos.east().north())
                || sourceWater(level, pos.west())
                        && sourceWater(level, pos.north())
                        && sourceWater(level, pos.west().north());
    }

    private static boolean sourceWater(ServerLevel level, BlockPos pos) {
        return level.getFluidState(pos).isSource() && level.getFluidState(pos).is(FluidTags.WATER);
    }

    private static int wheatCount(CompanionEntity companion) {
        int total = 0;
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.is(Items.WHEAT)) {
                total += stack.getCount();
            }
        }
        if (companion.getMainHandItem().is(Items.WHEAT)) {
            total += companion.getMainHandItem().getCount();
        }
        return total;
    }

    /** How many safe, ready-to-eat food items the companion holds; raw ingredients do not count. */
    private static int readyCount(CompanionEntity companion) {
        int total = 0;
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (isReadyFood(companion, stack)) {
                total += stack.getCount();
            }
        }
        if (isReadyFood(companion, companion.getMainHandItem())) {
            total += companion.getMainHandItem().getCount();
        }
        return total;
    }

    private static boolean isReadyFood(CompanionEntity companion, ItemStack stack) {
        return Food.isSafe(companion, stack, false) && !RAW_FOOD.contains(stack.getItem());
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private TaskStatus succeed(CompanionEntity companion) {
        int gained = Math.max(0, readyCount(companion) - readyAtStart);
        LinkedHashSet<String> kinds = new LinkedHashSet<>();
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (isReadyFood(companion, stack)) {
                kinds.add(idOf(stack));
            }
        }
        if (isReadyFood(companion, companion.getMainHandItem())) {
            kinds.add(idOf(companion.getMainHandItem()));
        }
        summary = kinds.isEmpty() ? "got " + gained + " food" : "got " + gained + " food: " + String.join(", ", kinds);
        return TaskStatus.SUCCESS;
    }

    private TaskStatus fail(String text) {
        summary = text;
        return TaskStatus.FAILED;
    }

    private static String entityId(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }

    private static void safeStop(Task task, TaskContext c) {
        try {
            task.stop(c);
        } catch (RuntimeException ignored) {
            // A subtask that cannot stop cleanly must not take the whole food quest down with it.
        }
    }

    @Override
    public void stop(TaskContext c) {
        if (current != null) {
            safeStop(current, c);
            current = null;
        }
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return "getting food: " + (currentOption == null ? "planning" : currentOption.shortName);
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
