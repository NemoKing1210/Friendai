package dev.friedai.task.farming;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.TaskContext;
import dev.friedai.task.mining.BlockBreaker;
import dev.friedai.task.nav.Approach;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * One pass over a farm: harvest, replant, sow empty farmland and optionally till soil next to water.
 * A tickable unit used by both the one-shot {@code farm} task and the open-ended {@code tend_farm}.
 */
public final class FarmPass {
    private enum Phase { HARVEST, REPLANT, PLANT, TILL, DONE }

    private enum Step { BUSY, IDLE }

    private enum Action { REPLANT, PLANT, TILL }

    private static final int TILL_CAP = 16;
    private static final int WATER_RADIUS = 4;
    private static final Item[] SEED_ORDER = {Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS};

    private final BlockPos center;
    private final int radius;
    private final @Nullable Item crop;
    private final boolean till;

    private final Map<String, Integer> harvested = new LinkedHashMap<>();
    private final Map<BlockPos, Item> replantQueue = new LinkedHashMap<>();
    private final Set<BlockPos> attempted = new HashSet<>();
    private final List<BlockPos> tillQueue = new ArrayList<>();
    private int tillIndex;

    private Phase phase = Phase.HARVEST;
    private BlockBreaker breaker;
    private BlockPos breakerPos;
    private Map<String, Integer> breakerDrops;
    private Approach berryApproach;
    private BlockPos berryPos;

    private Approach approach;
    private BlockPos actionPos;
    private Action action;
    private Item actionSeed;

    private int replanted;
    private int planted;
    private int tilled;
    private int noSeeds;
    private boolean noHoe;
    private boolean stopped;
    private FarmReport report;

    public FarmPass(BlockPos center, int radius, @Nullable Item crop, boolean till) {
        this.center = center.immutable();
        this.radius = Math.max(1, Math.min(16, radius));
        this.crop = crop;
        this.till = till;
    }

    /** Works a little each tick; true when the pass is over. */
    public boolean tick(TaskContext c) {
        if (stopped || phase == Phase.DONE) {
            return true;
        }
        while (phase != Phase.DONE) {
            Step step = switch (phase) {
                case HARVEST -> harvestTick(c);
                case REPLANT -> replantTick(c);
                case PLANT -> plantTick(c);
                case TILL -> tillTick(c);
                case DONE -> Step.IDLE;
            };
            if (step == Step.BUSY) {
                return false;
            }
            phase = switch (phase) {
                case HARVEST -> Phase.REPLANT;
                case REPLANT -> Phase.PLANT;
                case PLANT -> till ? Phase.TILL : Phase.DONE;
                case TILL -> Phase.DONE;
                case DONE -> Phase.DONE;
            };
        }
        finish();
        return true;
    }

    public FarmReport report() {
        if (report == null) {
            finish();
        }
        return report;
    }

    /** Stops navigation and any block breaking; the pass is over. */
    public void stop(TaskContext c) {
        stopped = true;
        if (breaker != null) {
            breaker.stop(c);
            breaker = null;
        }
        c.companion().getNavigation().stop();
    }

    private Step harvestTick(TaskContext c) {
        if (breaker != null) {
            BlockBreaker.State state = breaker.tick(c);
            if (state == BlockBreaker.State.WORKING) {
                return Step.BUSY;
            }
            if (state == BlockBreaker.State.BROKEN && breakerDrops != null) {
                for (Map.Entry<String, Integer> entry : breakerDrops.entrySet()) {
                    harvested.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }
            }
            breaker.stop(c);
            attempted.add(breakerPos);
            breaker = null;
            breakerPos = null;
            breakerDrops = null;
            return Step.BUSY;
        }
        if (berryPos != null) {
            Approach.State state = berryApproach.tick(c.companion());
            if (state == Approach.State.MOVING) {
                return Step.BUSY;
            }
            BlockPos pos = berryPos;
            if (state == Approach.State.IN_REACH && c.companion().hands().canInteract(pos)) {
                int before = c.companion().inventory().countItem(Items.SWEET_BERRIES);
                c.companion().hands().use(pos);
                c.companion().collectNearbyItems(4.0);
                int gained = c.companion().inventory().countItem(Items.SWEET_BERRIES) - before;
                if (gained > 0) {
                    harvested.merge(itemId(Items.SWEET_BERRIES), gained, Integer::sum);
                }
            }
            attempted.add(pos);
            berryPos = null;
            berryApproach = null;
            return Step.BUSY;
        }
        if (!hasRoom(c.companion())) {
            return Step.IDLE;
        }
        BlockPos target = nearestHarvestable(c);
        if (target == null) {
            return Step.IDLE;
        }
        Optional<Harvestable> found = Crops.harvestable(c.level(), target);
        if (found.isEmpty()) {
            attempted.add(target);
            return Step.BUSY;
        }
        Harvestable harvestable = found.get();
        if (harvestable.kind() == Harvestable.Kind.BERRIES) {
            berryPos = target;
            berryApproach = new Approach(target);
            return Step.BUSY;
        }
        BlockState state = c.level().getBlockState(target);
        breakerDrops = countDrops(c.level(), target, state, c.companion());
        breaker = new BlockBreaker(target, current -> Crops.harvestable(c.level(), target).isPresent());
        breakerPos = target;
        if (harvestable.replant() != null
                && (harvestable.kind() == Harvestable.Kind.CROP || harvestable.kind() == Harvestable.Kind.NETHER_WART)) {
            replantQueue.put(target, harvestable.replant());
        }
        return Step.BUSY;
    }

    private Step replantTick(TaskContext c) {
        if (action != Action.REPLANT) {
            actionPos = nextReplant();
            if (actionPos == null) {
                return Step.IDLE;
            }
            action = Action.REPLANT;
            actionSeed = replantQueue.get(actionPos);
            approach = new Approach(actionPos.below());
        }
        Approach.State state = approach.tick(c.companion());
        if (state == Approach.State.MOVING) {
            return Step.BUSY;
        }
        BlockPos pos = actionPos;
        BlockPos below = pos.below();
        if (state == Approach.State.IN_REACH
                && c.companion().hands().canInteract(below)
                && c.level().getBlockState(pos).isAir()
                && Crops.isReplantSurface(c.level().getBlockState(below), actionSeed)) {
            ItemStack seed = takeOne(c.companion(), actionSeed);
            if (seed.isEmpty()) {
                noSeeds++;
                attempted.add(pos);
            } else {
                InteractionResult result = c.companion().hands().place(seed, below, Direction.UP);
                if (result.consumesAction() && Crops.isCrop(c.level().getBlockState(pos))) {
                    replanted++;
                }
            }
        }
        replantQueue.remove(pos);
        attempted.add(pos);
        clearAction();
        return Step.BUSY;
    }

    private Step plantTick(TaskContext c) {
        if (action != Action.PLANT) {
            actionPos = nextEmptyFarmland(c);
            if (actionPos == null) {
                return Step.IDLE;
            }
            action = Action.PLANT;
            attempted.add(actionPos);
            actionSeed = chooseSeed(c.level(), actionPos, c.companion());
            if (actionSeed == null) {
                noSeeds++;
                clearAction();
                return Step.BUSY;
            }
            approach = new Approach(actionPos);
        }
        Approach.State state = approach.tick(c.companion());
        if (state == Approach.State.MOVING) {
            return Step.BUSY;
        }
        BlockPos pos = actionPos;
        if (state == Approach.State.IN_REACH
                && c.companion().hands().canInteract(pos)
                && c.level().getBlockState(pos).is(Blocks.FARMLAND)
                && c.level().getBlockState(pos.above()).isAir()) {
            ItemStack seed = takeOne(c.companion(), actionSeed);
            if (!seed.isEmpty()) {
                InteractionResult result = c.companion().hands().place(seed, pos, Direction.UP);
                if (result.consumesAction() && Crops.isCrop(c.level().getBlockState(pos.above()))) {
                    planted++;
                }
            }
        }
        clearAction();
        return Step.BUSY;
    }

    private Step tillTick(TaskContext c) {
        if (tillQueue.isEmpty() && tillIndex == 0 && !hasHoe(c.companion())) {
            noHoe = true;
            return Step.IDLE;
        }
        if (tillQueue.isEmpty() && tillIndex == 0) {
            buildTillQueue(c);
        }
        if (tillIndex >= tillQueue.size() || tilled >= TILL_CAP) {
            return Step.IDLE;
        }
        if (action != Action.TILL) {
            actionPos = tillQueue.get(tillIndex++);
            action = Action.TILL;
            approach = new Approach(actionPos);
        }
        Approach.State state = approach.tick(c.companion());
        if (state == Approach.State.MOVING) {
            return Step.BUSY;
        }
        BlockPos pos = actionPos;
        if (state == Approach.State.IN_REACH
                && c.companion().hands().canInteract(pos)
                && isTillCandidate(c.level(), pos)) {
            ItemStack hoe = takeHoe(c.companion());
            if (hoe.isEmpty()) {
                noHoe = true;
                clearAction();
                return Step.IDLE;
            }
            c.companion().hands().place(hoe, pos, Direction.UP);
            if (c.level().getBlockState(pos).is(Blocks.COARSE_DIRT)) {
                ItemStack again = takeHoe(c.companion());
                if (!again.isEmpty()) {
                    c.companion().hands().place(again, pos, Direction.UP);
                }
            }
            if (c.level().getBlockState(pos).is(Blocks.FARMLAND)) {
                tilled++;
                plantTilled(c, pos);
            }
        }
        clearAction();
        return Step.BUSY;
    }

    private void plantTilled(TaskContext c, BlockPos farmland) {
        Item seedItem = chooseSeed(c.level(), farmland, c.companion());
        if (seedItem == null) {
            noSeeds++;
            attempted.add(farmland);
            return;
        }
        ItemStack seed = takeOne(c.companion(), seedItem);
        if (seed.isEmpty()) {
            noSeeds++;
            return;
        }
        InteractionResult result = c.companion().hands().place(seed, farmland, Direction.UP);
        if (result.consumesAction() && Crops.isCrop(c.level().getBlockState(farmland.above()))) {
            planted++;
        }
        attempted.add(farmland);
    }

    private BlockPos nearestHarvestable(TaskContext c) {
        Vec3 at = c.companion().position();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz).immutable();
                    if (attempted.contains(pos) || Crops.harvestable(c.level(), pos).isEmpty()) {
                        continue;
                    }
                    double distance = Vec3.atCenterOf(pos).distanceToSqr(at);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private BlockPos nextReplant() {
        for (BlockPos pos : replantQueue.keySet()) {
            return pos;
        }
        return null;
    }

    private BlockPos nextEmptyFarmland(TaskContext c) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz).immutable();
                    if (attempted.contains(pos) || !c.level().getBlockState(pos).is(Blocks.FARMLAND)
                            || !c.level().getBlockState(pos.above()).isAir()) {
                        continue;
                    }
                    double distance = Vec3.atCenterOf(pos).distanceToSqr(c.companion().position());
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private @Nullable Item chooseSeed(ServerLevel level, BlockPos farmland, CompanionEntity companion) {
        if (crop != null) {
            return carried(companion, crop) ? crop : null;
        }
        Item common = mostCommonNeighbourSeed(level, farmland);
        if (common != null && carried(companion, common)) {
            return common;
        }
        for (Item seed : SEED_ORDER) {
            if (carried(companion, seed)) {
                return seed;
            }
        }
        return null;
    }

    private static @Nullable Item mostCommonNeighbourSeed(ServerLevel level, BlockPos pos) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                Crops.seedFor(level.getBlockState(pos.offset(dx, 0, dz)))
                        .ifPresent(seed -> counts.merge(seed, 1, Integer::sum));
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private void buildTillQueue(TaskContext c) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz).immutable();
                    if (isTillCandidate(c.level(), pos)) {
                        candidates.add(pos);
                    }
                }
            }
        }
        candidates.sort(Comparator.<BlockPos>comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(c.companion().position()))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ));
        tillQueue.addAll(candidates);
    }

    private static boolean isTillCandidate(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT_PATH) || state.is(Blocks.COARSE_DIRT))) {
            return false;
        }
        if (!level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        for (int dx = -WATER_RADIUS; dx <= WATER_RADIUS; dx++) {
            for (int dz = -WATER_RADIUS; dz <= WATER_RADIUS; dz++) {
                if (dx * dx + dz * dz > WATER_RADIUS * WATER_RADIUS) {
                    continue;
                }
                if (level.getBlockState(pos.offset(dx, 0, dz)).is(Blocks.WATER)
                        || level.getBlockState(pos.offset(dx, -1, dz)).is(Blocks.WATER)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasRoom(CompanionEntity companion) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            if (companion.inventory().getItem(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasHoe(CompanionEntity companion) {
        if (companion.getMainHandItem().is(ItemTags.HOES)) {
            return true;
        }
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            if (companion.inventory().getItem(slot).is(ItemTags.HOES)) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack takeHoe(CompanionEntity companion) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.is(ItemTags.HOES)) {
                companion.inventory().setItem(slot, ItemStack.EMPTY);
                companion.inventory().setChanged();
                return stack;
            }
        }
        ItemStack hand = companion.getMainHandItem();
        if (hand.is(ItemTags.HOES)) {
            companion.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            return hand;
        }
        return ItemStack.EMPTY;
    }

    private static boolean carried(CompanionEntity companion, Item item) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            if (companion.inventory().getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
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

    private static Map<String, Integer> countDrops(ServerLevel level, BlockPos pos, BlockState state, CompanionEntity companion) {
        Map<String, Integer> drops = new LinkedHashMap<>();
        for (ItemStack stack : net.minecraft.world.level.block.Block.getDrops(
                state, level, pos, level.getBlockEntity(pos), companion, ItemStack.EMPTY)) {
            drops.merge(itemId(stack.getItem()), stack.getCount(), Integer::sum);
        }
        return drops;
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private void clearAction() {
        approach = null;
        actionPos = null;
        action = null;
        actionSeed = null;
    }

    private void finish() {
        if (report == null) {
            report = new FarmReport(new LinkedHashMap<>(harvested), replanted, planted, tilled, noSeeds, noHoe);
        }
    }
}
