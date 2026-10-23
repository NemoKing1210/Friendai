package dev.friendai.task.build;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.TaskContext;
import dev.friendai.task.interact.PlaceSpot;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.mining.BlockBreaker;
import dev.friendai.task.nav.Approach;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Temporary dirt or cobblestone blocks that let the companion build where a standing player could not: one block as a
 * support under a block with nothing to attach to, and a pillar the companion climbs by placing a block at its feet,
 * stepping onto it and repeating.
 *
 * <p>Everything here comes from the companion's own inventory, is recorded in {@link #placed()}, and is removed again
 * top-down. Only a recorded position that still holds dirt or cobblestone is ever broken. Only the surplus the
 * blueprint does not need may be spent: the scaffold never takes the material of a wall or a foundation.
 */
public final class Scaffold {
    public enum State { CLIMBING, READY, FAILED }

    /** The failure text when the companion carries nothing left to scaffold with. */
    public static final String OUT_OF_MATERIAL = "I ran out of dirt or cobblestone for scaffolding";
    /** How many blocks one pillar may climb above where it started. */
    public static final int MAX_PILLAR = 16;

    private final CompanionEntity companion;
    private final List<BlockPos> placed = new ArrayList<>();
    private Map<Item, Integer> reserved = Map.of();
    private String failure = "";
    private boolean climbing;
    private int climbX;
    private int climbZ;
    private int climbBaseY;
    private List<BlockPos> removal;
    private int removalIndex;
    private BlockBreaker breaker;

    public Scaffold(CompanionEntity companion) {
        this.companion = companion;
    }

    /** Keeps {@code needed} items for the blueprint: only what is left over may be spent on scaffolding. */
    public void reserve(Map<Item, Integer> needed) {
        this.reserved = Map.copyOf(needed);
    }

    /**
     * One item of the reserve was placed in the blueprint, so the blueprint needs that much less. Without this the
     * reserve would keep counting material the build has already spent, and the scaffold would starve itself after
     * the foundation.
     */
    public void spend(Item item) {
        int left = reserved.getOrDefault(item, 0);
        if (left <= 0) {
            return;
        }
        Map<Item, Integer> remaining = new HashMap<>(reserved);
        if (left == 1) {
            remaining.remove(item);
        } else {
            remaining.put(item, left - 1);
        }
        reserved = Map.copyOf(remaining);
    }

    /**
     * Whether the companion carries dirt or cobblestone the blueprint does not need. Without a surplus a tall build
     * must be refused up front instead of spending its own wall or foundation material on a pillar.
     */
    public boolean canScaffold() {
        return surplus(Items.DIRT) > 0 || surplus(Items.COBBLESTONE) > 0;
    }

    /** The blocks this scaffold placed and that are expected to be removed again. */
    public List<BlockPos> placed() {
        return List.copyOf(placed);
    }

    /**
     * Takes over scaffold blocks that are already in the world, from a build that was saved and reloaded. Nothing is
     * placed and no material is spent: the blocks are only recorded so {@link #removeAll()} removes them at the end.
     * A recorded position that no longer holds dirt or cobblestone is skipped there.
     */
    public void adopt(List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            remember(pos);
        }
    }

    /** How many blocks the scaffold placed in total. */
    public int used() {
        return placed.size();
    }

    /** Why the last {@link #support} or {@link #pillarTo} failed. */
    public String failure() {
        return failure;
    }

    /** Whether the last failure was a lack of dirt or cobblestone. */
    public boolean outOfMaterial() {
        return OUT_OF_MATERIAL.equals(failure);
    }

    /**
     * Places one scaffold block at {@code below} so the block above it has a support face. Returns false when no
     * support can be placed there (no sturdy neighbour, the cell is not free, or no material).
     */
    public boolean support(BlockPos below) {
        failure = "";
        ServerLevel level = level();
        BlockState there = level.getBlockState(below);
        if (isScaffold(there)) {
            return true;
        }
        if (below.getY() < level.getMinY() || !there.canBeReplaced() || !there.getFluidState().isEmpty()) {
            return false;
        }
        if (!level.getBlockState(below.above()).canBeReplaced()) {
            return false;
        }
        if (companion.getBoundingBox().intersects(new AABB(below))) {
            return false;
        }
        Optional<PlaceSpot.Spot> spot = PlaceSpot.spotAt(level, below);
        if (spot.isEmpty()) {
            return false;
        }
        ItemStack stack = takeMaterial();
        if (stack.isEmpty()) {
            failure = OUT_OF_MATERIAL;
            return false;
        }
        companion.hands().place(stack, spot.get().against(), spot.get().face());
        if (!isScaffold(level.getBlockState(below))) {
            failure = "can't place a scaffold block at " + coords(below);
            return false;
        }
        remember(below);
        return true;
    }

    /**
     * Climbs the companion's own column one block per call until its feet reach {@code y}: each step places a scaffold
     * block where the companion stood and moves it onto that block. Returns {@link State#CLIMBING} while more steps
     * are needed, {@link State#READY} once the feet are at or above {@code y}, and {@link State#FAILED} when the
     * column is blocked, a block has no support face or the inventory has no scaffolding left.
     */
    public State pillarTo(int y) {
        failure = "";
        ServerLevel level = level();
        BlockPos feet = companion.blockPosition();
        if (feet.getY() >= y) {
            climbing = false;
            return State.READY;
        }
        if (!climbing || feet.getX() != climbX || feet.getZ() != climbZ || feet.getY() < climbBaseY) {
            // A climb in a new column (or one that fell) starts its own 16-block budget.
            climbing = true;
            climbX = feet.getX();
            climbZ = feet.getZ();
            climbBaseY = feet.getY();
        }
        if (y - climbBaseY > MAX_PILLAR) {
            failure = "a scaffold taller than " + MAX_PILLAR + " blocks isn't possible";
            return State.FAILED;
        }
        BlockPos up = feet.above();
        if (!level.getBlockState(up).canBeReplaced() || !level.getBlockState(up.above()).canBeReplaced()) {
            failure = "can't scaffold up through " + coords(up);
            return State.FAILED;
        }
        Optional<PlaceSpot.Spot> spot = PlaceSpot.spotAt(level, feet);
        if (spot.isEmpty()) {
            failure = "can't place a scaffold block at " + coords(feet) + ": nothing to place it against";
            return State.FAILED;
        }
        ItemStack stack = takeMaterial();
        if (stack.isEmpty()) {
            failure = OUT_OF_MATERIAL;
            return State.FAILED;
        }
        companion.getNavigation().stop();
        companion.teleportTo(up.getX() + 0.5, up.getY(), up.getZ() + 0.5);
        companion.hands().place(stack, spot.get().against(), spot.get().face());
        if (!isScaffold(level.getBlockState(feet))) {
            failure = "can't place a scaffold block at " + coords(feet);
            return State.FAILED;
        }
        remember(feet);
        return companion.blockPosition().getY() >= y ? State.READY : State.CLIMBING;
    }

    /**
     * Breaks every block this scaffold placed, highest first. The companion is moved to a standing spot within reach
     * of each block instead of walking to it, so removal does not depend on pathfinding. Returns
     * {@link State#CLIMBING} while a block is being broken, {@link State#READY} when none is left, and
     * {@link State#FAILED} when one cannot be reached or is protected. A block that no longer holds what the
     * scaffold placed is left alone.
     */
    public State removeAll() {
        ServerLevel level = level();
        if (removal == null) {
            removal = new ArrayList<>(placed);
            removal.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed());
            removalIndex = 0;
        }
        while (removalIndex < removal.size()) {
            if (breaker != null) {
                // The block is already gone while the drops are being collected; the state check below must wait
                // until the breaker is done, or the position would be skipped twice.
                switch (breaker.tick(new TaskContext(companion, level))) {
                    case WORKING -> {
                        return State.CLIMBING;
                    }
                    case BROKEN, GONE -> {
                        breaker = null;
                        removalIndex++;
                    }
                    case UNREACHABLE, PROTECTED -> {
                        breaker = null;
                        return State.FAILED;
                    }
                }
                continue;
            }
            BlockPos pos = removal.get(removalIndex);
            if (!isScaffold(level.getBlockState(pos))) {
                removalIndex++;
                continue;
            }
            if (!reachable(pos)) {
                return State.FAILED;
            }
            breaker = new BlockBreaker(pos, Scaffold::isScaffold);
        }
        return State.READY;
    }

    /** Puts the companion within reach of pos, stepping to a free standing spot when it is not there already. */
    private boolean reachable(BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        if (companion.getEyePosition().distanceTo(center) <= Approach.REACH) {
            return true;
        }
        BlockPos spot = standingSpotNear(pos);
        if (spot == null) {
            return false;
        }
        companion.getNavigation().stop();
        companion.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        companion.getLookControl().setLookAt(center.x, center.y, center.z);
        return companion.getEyePosition().distanceTo(center) <= Approach.REACH;
    }

    /**
     * The free standing cell nearest to pos from which pos is in reach. The cell above the block is a candidate too,
     * so a pillar standing on its own is dismantled from the top down.
     */
    private BlockPos standingSpotNear(BlockPos pos) {
        ServerLevel level = level();
        Vec3 center = Vec3.atCenterOf(pos);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = 1; dy >= -3; dy--) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dy == 0 && dx == 0 && dz == 0) {
                        continue;
                    }
                    if (Math.sqrt(dx * dx + dz * dz) > 3.0) {
                        continue;
                    }
                    BlockPos candidate = pos.offset(dx, dy, dz);
                    if (!freeStanding(level, candidate)) {
                        continue;
                    }
                    Vec3 eye = new Vec3(candidate.getX() + 0.5, candidate.getY() + 1.62, candidate.getZ() + 0.5);
                    double distance = eye.distanceTo(center);
                    if (distance > Approach.REACH || distance >= bestDistance) {
                        continue;
                    }
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static boolean freeStanding(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    /** How many of the placed blocks still hold the scaffold material. */
    public int left() {
        ServerLevel level = level();
        int left = 0;
        for (BlockPos pos : placed) {
            if (isScaffold(level.getBlockState(pos))) {
                left++;
            }
        }
        return left;
    }

    /** Stops navigation and the break in progress. Never writes to the world. */
    public void stop() {
        if (breaker != null) {
            breaker.stop(new TaskContext(companion, level()));
            breaker = null;
        }
        climbing = false;
    }

    /** Dirt, or cobblestone when that is the one the foundation will not use; only the surplus is considered. */
    private Item preferred() {
        int dirt = surplus(Items.DIRT);
        int cobble = surplus(Items.COBBLESTONE);
        if (dirt <= 0) {
            return cobble > 0 ? Items.COBBLESTONE : null;
        }
        if (cobble <= 0) {
            return Items.DIRT;
        }
        // Site prefers dirt for the foundation when there is more of it, so the scaffold takes the other one.
        return dirt > cobble ? Items.COBBLESTONE : Items.DIRT;
    }

    /** How much of {@code item} is carried beyond what the blueprint needs. */
    private int surplus(Item item) {
        return count(companion, item) - reserved.getOrDefault(item, 0);
    }

    private ItemStack takeMaterial() {
        Item item = preferred();
        if (item == null) {
            return ItemStack.EMPTY;
        }
        ItemMatcher matcher = ItemMatcher.parse(BuiltInRegistries.ITEM.getKey(item).toString()).orElse(null);
        if (matcher == null) {
            return ItemStack.EMPTY;
        }
        List<ItemStack> removed = InventoryOps.remove(companion.inventory(), matcher, 1);
        return removed.isEmpty() ? ItemStack.EMPTY : removed.get(0);
    }

    private void remember(BlockPos pos) {
        if (!placed.contains(pos)) {
            placed.add(pos);
        }
    }

    private ServerLevel level() {
        return (ServerLevel) companion.level();
    }

    private static boolean isScaffold(BlockState state) {
        return state.is(Blocks.DIRT) || state.is(Blocks.COBBLESTONE);
    }

    private static int count(CompanionEntity companion, Item item) {
        int total = 0;
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
