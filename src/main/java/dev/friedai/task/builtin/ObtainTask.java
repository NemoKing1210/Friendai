package dev.friedai.task.builtin;

import dev.friedai.crafting.obtain.ObtainPlan;
import dev.friedai.crafting.obtain.ObtainPlanner;
import dev.friedai.crafting.obtain.ObtainStep;
import dev.friedai.crafting.obtain.ObtainText;
import dev.friedai.crafting.obtain.WorldFacts;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.Task;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.crafting.RecipeIndex;
import dev.friedai.task.interact.ContainerAccess;
import dev.friedai.task.inventory.InventoryOps;
import dev.friedai.task.inventory.ItemMatcher;
import dev.friedai.task.mining.BlockMatcher;
import dev.friedai.task.mining.BlockSearch;
import dev.friedai.task.obtain.DropIndex;
import dev.friedai.task.obtain.WorldScan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;

/**
 * Gets an item by any means: scans, plans the whole chain, runs the first step with the matching task and
 * re-plans from the new state after each step. Stops on its step and tick limits, or when the same step fails
 * twice in a row.
 */
public final class ObtainTask implements Task {
    public static final int DEFAULT_MAX_STEPS = 40;
    public static final int DEFAULT_MAX_TICKS = 24_000;
    public static final int DEFAULT_EXPLORE_RADIUS = 64;
    public static final int DEFAULT_EXPLORE_BLOCKS = 128;
    public static final int DEFAULT_EXPLORE_TICKS = 6_000;
    private static final int NOTICE_INTERVAL = 600;
    private static final int FREE_SLOTS = 2;
    private static final int CONTAINER_RADIUS = 16;
    private static final int MINING_RADIUS = 32;
    private static final int HUNT_RADIUS = 24;
    private static final int FARM_RADIUS = 8;
    private static final int SMELT_WAIT_TICKS = 1_200;

    private final String item;
    private final int count;
    private final boolean useContainers;
    private final boolean explore;
    private final int maxSteps;
    private final int maxTicks;
    private final int exploreRadius;
    private final int exploreBlocks;
    private final int exploreTicks;

    private final List<String> history = new ArrayList<>();
    private Task current;
    private ObtainStep currentStep;
    private String summary = "";
    private String lastFailedKey;
    private int ticks;
    private int stepsRun;
    private int lastNoticeTick = -NOTICE_INTERVAL;

    public ObtainTask(String item, int count, boolean useContainers, boolean explore) {
        this(item, count, useContainers, explore, DEFAULT_MAX_STEPS, DEFAULT_MAX_TICKS,
                DEFAULT_EXPLORE_RADIUS, DEFAULT_EXPLORE_BLOCKS, DEFAULT_EXPLORE_TICKS);
    }

    /** The full constructor, for tests with tighter limits. */
    public ObtainTask(String item, int count, boolean useContainers, boolean explore, int maxSteps, int maxTicks,
            int exploreRadius, int exploreBlocks, int exploreTicks) {
        this.item = item;
        this.count = count;
        this.useContainers = useContainers;
        this.explore = explore;
        this.maxSteps = maxSteps;
        this.maxTicks = maxTicks;
        this.exploreRadius = exploreRadius;
        this.exploreBlocks = exploreBlocks;
        this.exploreTicks = exploreTicks;
    }

    @Override
    public String type() {
        return "obtain_item";
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public void start(TaskContext c) {
        current = null;
        currentStep = null;
        summary = "";
        lastFailedKey = null;
        ticks = 0;
        stepsRun = 0;
        lastNoticeTick = -NOTICE_INTERVAL;
        history.clear();
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (++ticks > maxTicks) {
            return fail("ran out of time; have " + have(c.companion()) + " of " + count + " " + item);
        }
        if (current != null) {
            TaskStatus status = current.tick(c);
            if (status == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            String subSummary = current.resultSummary();
            safeStop(current, c);
            ObtainStep step = currentStep;
            current = null;
            currentStep = null;
            if (status == TaskStatus.SUCCESS) {
                stepsRun++;
                history.add(pastText(step));
                lastFailedKey = null;
            } else {
                String key = stepKey(step);
                if (key.equals(lastFailedKey)) {
                    return fail("stuck at " + ObtainText.stepText(step) + ": " + subSummary);
                }
                lastFailedKey = key;
                return TaskStatus.RUNNING;
            }
        }
        return startNext(c);
    }

    private TaskStatus startNext(TaskContext c) {
        CompanionEntity companion = c.companion();
        int have = have(companion);
        if (have >= count) {
            return succeed();
        }
        if (stepsRun >= maxSteps) {
            return fail("gave up after " + maxSteps + " steps; have " + have + " of " + count + " " + item);
        }
        ServerLevel level = c.level();
        WorldFacts facts = WorldScan.scan(level, companion, WorldScan.BLOCK_RADIUS, useContainers, explore);
        ObtainPlan plan = ObtainPlanner.plan(item, count, inventoryMap(companion), RecipeIndex.of(level),
                DropIndex.of(level), facts);
        if (!plan.ok()) {
            return fail("can't get " + count + " " + item + " here: "
                    + ObtainText.missingText(plan.missing(), !explore));
        }
        if (plan.steps().isEmpty()) {
            return fail("can't get " + count + " " + item + " here: nothing to do");
        }
        Optional<String> room = ensureRoom(c, plan);
        if (room.isPresent()) {
            return fail(room.get());
        }
        currentStep = plan.steps().get(0);
        current = subtask(c, currentStep);
        if (ticks - lastNoticeTick >= NOTICE_INTERVAL) {
            lastNoticeTick = ticks;
            companion.taskRunner().notice("obtain " + item + ": " + ObtainText.stepText(currentStep)
                    + " (step " + (stepsRun + 1) + ")");
        }
        current.start(c);
        return TaskStatus.RUNNING;
    }

    private Task subtask(TaskContext c, ObtainStep step) {
        ServerLevel level = c.level();
        return switch (step) {
            case ObtainStep.Take take -> new TakeItemsTask(itemMatcher(take.item()), take.count(), null);
            case ObtainStep.Craft craft -> new CraftTask(craft.item(), craft.count(), CraftTask.StationPolicy.AUTO);
            case ObtainStep.Smelt smelt -> new SmeltTask(itemMatcher(smelt.input()), smelt.count(), null, null, true,
                    CraftTask.StationPolicy.AUTO, SMELT_WAIT_TICKS);
            case ObtainStep.Mine mine -> new MineBlocksTask(blockMatcher(mine.block()), mine.count(), MINING_RADIUS);
            case ObtainStep.Hunt hunt -> new HuntTask(
                    type -> entityId(type).equals(hunt.mob()), hunt.mob(), hunt.count(), HUNT_RADIUS);
            case ObtainStep.Harvest harvest -> {
                BlockMatcher crop = blockMatcher(harvest.crop());
                BlockPos center = BlockSearch.nearest(level, c.companion().blockPosition(), CONTAINER_RADIUS,
                        crop, Set.of()).orElse(c.companion().blockPosition());
                yield new FarmTask(center, FARM_RADIUS, null, false);
            }
            case ObtainStep.Shear shear -> new ShearTask(entityType(shear.mob()), shear.count());
            case ObtainStep.Milk milk -> new MilkTask(milk.count(), null);
            case ObtainStep.Explore explore -> new ExploreTask(blockMatcher(explore.block()), explore.down(),
                    explore.depthY(), exploreRadius, exploreBlocks, exploreTicks);
        };
    }

    private Optional<String> ensureRoom(TaskContext c, ObtainPlan plan) {
        CompanionEntity companion = c.companion();
        if (emptySlots(companion) >= FREE_SLOTS) {
            return Optional.empty();
        }
        if (!useContainers) {
            return Optional.of("my inventory is full");
        }
        ServerLevel level = c.level();
        BlockPos pos = ContainerAccess.nearest(level, companion.blockPosition(), CONTAINER_RADIUS).orElse(null);
        if (pos == null || !companion.hands().canInteract(pos)) {
            return Optional.of("my inventory is full");
        }
        Container container = ContainerAccess.at(level, pos).orElse(null);
        if (container == null) {
            return Optional.of("my inventory is full");
        }
        Set<String> keep = itemsUsed(plan);
        keep.add(item);
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (keep.contains(id)) {
                continue;
            }
            companion.inventory().setItem(slot, ContainerAccess.insert(container, stack));
        }
        companion.inventory().setChanged();
        container.setChanged();
        return emptySlots(companion) >= FREE_SLOTS ? Optional.empty() : Optional.of("my inventory is full");
    }

    private static Set<String> itemsUsed(ObtainPlan plan) {
        Set<String> used = new LinkedHashSet<>();
        for (ObtainStep step : plan.steps()) {
            switch (step) {
                case ObtainStep.Take take -> used.add(take.item());
                case ObtainStep.Craft craft -> used.add(craft.item());
                case ObtainStep.Smelt smelt -> {
                    used.add(smelt.input());
                    used.add(smelt.output());
                }
                case ObtainStep.Mine mine -> {
                    used.add(mine.block());
                    if (mine.tool().vanillaItem() != null) {
                        used.add(mine.tool().vanillaItem());
                    }
                }
                case ObtainStep.Hunt hunt -> used.add(hunt.mob());
                case ObtainStep.Harvest harvest -> used.add(harvest.crop());
                case ObtainStep.Shear shear -> used.add(shear.mob());
                case ObtainStep.Milk ignored -> {
                }
                case ObtainStep.Explore ignored -> {
                }
            }
        }
        return used;
    }

    private TaskStatus succeed() {
        StringBuilder text = new StringBuilder("got ").append(count).append(' ').append(item)
                .append(" in ").append(stepsRun).append(" steps");
        if (!history.isEmpty()) {
            text.append(": ");
            int shown = Math.min(history.size(), 8);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(history.get(i));
            }
            if (history.size() > shown) {
                text.append(", …");
            }
        }
        summary = text.toString();
        return TaskStatus.SUCCESS;
    }

    private TaskStatus fail(String text) {
        summary = text;
        return TaskStatus.FAILED;
    }

    private int have(CompanionEntity companion) {
        return InventoryOps.count(companion.inventory(), itemMatcher(item));
    }

    private static int emptySlots(CompanionEntity companion) {
        int empty = 0;
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            if (companion.inventory().getItem(slot).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    private static Map<String, Integer> inventoryMap(CompanionEntity companion) {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            account(items, companion.inventory().getItem(slot));
        }
        account(items, companion.getMainHandItem());
        return items;
    }

    private static void account(Map<String, Integer> items, ItemStack stack) {
        if (!stack.isEmpty()) {
            items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
    }

    private static ItemMatcher itemMatcher(String id) {
        return ItemMatcher.parse(id).orElseThrow(() -> new IllegalArgumentException("unknown item: " + id));
    }

    private static BlockMatcher blockMatcher(String id) {
        return BlockMatcher.parse(id).orElseThrow(() -> new IllegalArgumentException("unknown block: " + id));
    }

    private static EntityType<?> entityType(String id) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(parsed)) {
            throw new IllegalArgumentException("unknown mob: " + id);
        }
        return BuiltInRegistries.ENTITY_TYPE.getValue(parsed);
    }

    private static String entityId(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }

    private static String stepKey(ObtainStep step) {
        return switch (step) {
            case ObtainStep.Take take -> "take:" + take.item();
            case ObtainStep.Craft craft -> "craft:" + craft.item();
            case ObtainStep.Smelt smelt -> "smelt:" + smelt.input() + "->" + smelt.output();
            case ObtainStep.Mine mine -> "mine:" + mine.block();
            case ObtainStep.Hunt hunt -> "hunt:" + hunt.mob();
            case ObtainStep.Harvest harvest -> "harvest:" + harvest.crop();
            case ObtainStep.Shear shear -> "shear:" + shear.mob();
            case ObtainStep.Milk ignored -> "milk";
            case ObtainStep.Explore explore -> "explore:" + explore.block();
        };
    }

    private static String pastText(ObtainStep step) {
        return switch (step) {
            case ObtainStep.Take take -> "took " + take.count() + " " + take.item();
            case ObtainStep.Craft craft -> craft.count() == 1
                    ? "crafted " + craft.item()
                    : "crafted " + craft.count() + " × " + craft.item();
            case ObtainStep.Smelt smelt -> "smelted " + smelt.count() + " " + smelt.input();
            case ObtainStep.Mine mine -> "mined " + mine.count() + " " + mine.block();
            case ObtainStep.Hunt hunt -> "hunted " + hunt.count() + " " + hunt.mob();
            case ObtainStep.Harvest harvest -> "harvested " + harvest.count() + " " + harvest.crop();
            case ObtainStep.Shear shear -> "sheared " + shear.count() + " " + shear.mob();
            case ObtainStep.Milk milk -> "milked " + milk.count();
            case ObtainStep.Explore explore -> "explored for " + explore.block();
        };
    }

    private static void safeStop(Task task, TaskContext c) {
        try {
            task.stop(c);
        } catch (RuntimeException ignored) {
            // A task that cannot stop cleanly must not take the whole obtain down with it.
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
        String step = currentStep == null
                ? "planning"
                : ObtainText.stepText(currentStep);
        return "getting " + count + " " + item + ": step " + (stepsRun + 1) + " — " + step;
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
