package dev.friendai.task.build;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.creative.CreativeAction;
import dev.friendai.task.TaskContext;
import dev.friendai.task.interact.PlaceSpot;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.CreativeItemLease;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.mining.BlockBreaker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

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

    private enum Pillar { IDLE, JUMPING, PLACING }
    private static final int JUMP_TIMEOUT = 40;
    private Pillar pillarPhase = Pillar.IDLE;
    private BlockPos pillarFeet;
    private int pillarTicks;

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
    /** The block being taken out from under the feet, one step at a time. */
    private BlockBreaker descentBreaker;

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

    public boolean canScaffold(TaskContext context) {
        return canScaffold() || context.canUseCreativeAction(CreativeAction.FREE_MATERIALS);
    }

    /**
     * How many more scaffold blocks the companion can spend: the surplus dirt plus the surplus cobblestone, or an
     * effectively unlimited number when the run may use creative materials. A task that only checks
     * {@link #canScaffold()} starts a ten-block climb with one block in the bag and fails halfway.
     */
    public int available(TaskContext context) {
        if (context.canUseCreativeAction(CreativeAction.FREE_MATERIALS)) {
            return Integer.MAX_VALUE / 4;
        }
        return surplus(Items.DIRT) + surplus(Items.COBBLESTONE);
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
        return support(new TaskContext(companion, level()), below);
    }

    public boolean support(TaskContext context, BlockPos below) {
        return support(context, below, below.above());
    }

    /**
     * Places one scaffold block at {@code cell} to give {@code target} a face to click, wherever the target stands
     * beside it. Returns false when the support cannot be placed there (no sturdy neighbour, the cell is not free,
     * the target is no longer free, or no material).
     */
    public boolean support(TaskContext context, BlockPos cell, BlockPos target) {
        failure = "";
        ServerLevel level = level();
        BlockState there = level.getBlockState(cell);
        if (isScaffold(there)) {
            return true;
        }
        if (cell.getY() < level.getMinY() || !there.canBeReplaced() || !there.getFluidState().isEmpty()) {
            return false;
        }
        if (!level.getBlockState(target).canBeReplaced()) {
            return false;
        }
        if (companion.getBoundingBox().intersects(new AABB(cell))) {
            return false;
        }
        Optional<PlaceSpot.Spot> spot = PlaceSpot.spotAt(level, cell);
        if (spot.isEmpty()) {
            return false;
        }
        CreativeItemLease lease = takeMaterial(context);
        if (lease == null) {
            failure = OUT_OF_MATERIAL;
            return false;
        }
        lease.place(companion.hands(), spot.get().against(), spot.get().face());
        if (!isScaffold(level.getBlockState(cell))) {
            failure = "can't place a scaffold block at " + coords(cell);
            return false;
        }
        remember(cell);
        return true;
    }

    /**
     * Climbs the companion's own column until its feet reach {@code y}: each step jumps and places a scaffold
     * block at the old feet once the companion has risen above it. Returns {@link State#CLIMBING} while more steps
     * are needed, {@link State#READY} once the feet are at or above {@code y}, and {@link State#FAILED} when the
     * column is blocked, a block has no support face or the inventory has no scaffolding left.
     */
    public State pillarTo(int y) {
        return pillarTo(new TaskContext(companion, level()), y, MAX_PILLAR);
    }

    public State pillarTo(TaskContext context, int y) {
        return pillarTo(context, y, MAX_PILLAR);
    }

    /**
     * Climbs to {@code y} with at most {@code maxPillar} blocks above where the climb started. The tree skill passes
     * its own cap: a tall tree is the one case where more than {@link #MAX_PILLAR} is worth the material.
     */
    public State pillarTo(TaskContext context, int y, int maxPillar) {
        failure = "";
        ServerLevel level = level();
        if (pillarPhase == Pillar.JUMPING) {
            if (++pillarTicks > JUMP_TIMEOUT) {
                resetPillar();
                failure = "couldn't jump onto the scaffold block";
                return State.FAILED;
            }
            if (companion.getY() >= pillarFeet.getY() + 1.0) {
                pillarPhase = Pillar.PLACING;
            } else if (companion.onGround()) {
                companion.getJumpControl().jump();
            }
            return State.CLIMBING;
        }
        if (pillarPhase == Pillar.PLACING) {
            Optional<PlaceSpot.Spot> spot = PlaceSpot.spotAt(level, pillarFeet);
            if (spot.isEmpty()) {
                failure = "can't place a scaffold block at " + coords(pillarFeet);
                resetPillar();
                return State.FAILED;
            }
            CreativeItemLease lease = takeMaterial(context);
            if (lease == null) {
                failure = OUT_OF_MATERIAL;
                resetPillar();
                return State.FAILED;
            }
            lease.place(companion.hands(), spot.get().against(), spot.get().face());
            if (!isScaffold(level.getBlockState(pillarFeet))) {
                // CompanionHands returns the unconsumed item even when placement fails.
                failure = "can't place a scaffold block at " + coords(pillarFeet);
                resetPillar();
                return State.FAILED;
            }
            remember(pillarFeet);
            resetPillar();
            return companion.blockPosition().getY() >= y ? State.READY : State.CLIMBING;
        }
        BlockPos feet = companion.blockPosition();
        if (feet.getY() >= y) {
            climbing = false;
            return State.READY;
        }
        if (!climbing || feet.getX() != climbX || feet.getZ() != climbZ || feet.getY() < climbBaseY) {
            climbing = true;
            climbX = feet.getX();
            climbZ = feet.getZ();
            climbBaseY = feet.getY();
        }
        if (y - climbBaseY > maxPillar) {
            failure = "a scaffold taller than " + maxPillar + " blocks isn't possible";
            return State.FAILED;
        }
        BlockPos up = feet.above();
        if (!level.getBlockState(up).canBeReplaced() || !level.getBlockState(up.above()).canBeReplaced()) {
            failure = "can't scaffold up through " + coords(up);
            return State.FAILED;
        }
        if (PlaceSpot.spotAt(level, feet).isEmpty()) {
            failure = "can't place a scaffold block at " + coords(feet) + ": nothing to place it against";
            return State.FAILED;
        }
        if (!canScaffold(context)) {
            failure = OUT_OF_MATERIAL;
            return State.FAILED;
        }
        pillarFeet = feet;
        pillarTicks = 0;
        pillarPhase = Pillar.JUMPING;
        companion.getNavigation().stop();
        companion.getJumpControl().jump();
        return State.CLIMBING;
    }

    /**
     * Descends by taking the recorded column apart under its feet: the block under the feet is broken, the companion
     * falls a single block onto the next, and the loop repeats until the feet reach {@code feetY} or the block below
     * is no longer this scaffold's. It never breaks the last support into a longer fall; a missing landing is a
     * failure, not a drop.
     */
    public State descendTo(TaskContext context, int feetY) {
        return descend(context, feetY);
    }

    /** Takes the whole remaining column down under the feet, however far the ground is. */
    public State takeDown(TaskContext context) {
        return descend(context, Integer.MIN_VALUE);
    }

    private State descend(TaskContext context, int feetY) {
        failure = "";
        ServerLevel level = level();
        if (descentBreaker != null) {
            switch (descentBreaker.tick(context)) {
                case WORKING -> {
                    return State.CLIMBING;
                }
                case BROKEN, GONE -> {
                    descentBreaker = null;
                    return State.CLIMBING;
                }
                case UNREACHABLE -> {
                    descentBreaker = null;
                    failure = "couldn't reach the scaffold under my feet";
                    return State.FAILED;
                }
                case PROTECTED -> {
                    descentBreaker = null;
                    failure = "the scaffold under my feet is protected";
                    return State.FAILED;
                }
                case OUT_OF_SIGHT -> {
                    descentBreaker = null;
                    return State.CLIMBING;
                }
            }
        }
        if (companion.blockPosition().getY() <= feetY) {
            return State.READY;
        }
        if (!companion.onGround()) {
            return State.CLIMBING;
        }
        BlockPos support = companion.blockPosition().below();
        if (!stillScaffold(level, support)) {
            return State.READY;
        }
        BlockPos landing = support.below();
        boolean grounded = level.hasChunkAt(landing)
                && (!level.getBlockState(landing).getCollisionShape(level, landing).isEmpty()
                        || stillScaffold(level, landing));
        if (!grounded) {
            failure = "there is nothing to land on under the scaffold at " + coords(support);
            return State.FAILED;
        }
        descentBreaker = new BlockBreaker(support, state -> stillScaffold(level, support), false, true);
        return State.CLIMBING;
    }

    private boolean stillScaffold(ServerLevel level, BlockPos pos) {
        return placed.contains(pos) && level.hasChunkAt(pos) && isScaffold(level.getBlockState(pos));
    }

    private void resetPillar() {
        pillarPhase = Pillar.IDLE;
        pillarFeet = null;
        pillarTicks = 0;
    }

    /**
     * Breaks every block this scaffold placed, highest first. The block breaker walks within reach of each block.
     * Returns
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
                    case UNREACHABLE -> {
                        breaker = null;
                        failure = "couldn't reach scaffold at " + coords(removal.get(removalIndex));
                        return State.FAILED;
                    }
                    case PROTECTED -> {
                        breaker = null;
                        BlockPos blocked = removal.get(removalIndex);
                        if (!companion.hands().canInteract(blocked)) {
                            failure = "scaffold is protected at " + coords(blocked);
                        } else if (level.getBlockState(blocked).getDestroySpeed(level, blocked) < 0) {
                            failure = "scaffold cannot be broken at " + coords(blocked);
                        } else {
                            failure = "scaffold breaker was blocked at " + coords(blocked);
                        }
                        return State.FAILED;
                    }
                    case OUT_OF_SIGHT -> {
                        breaker = null;
                        failure = "scaffold is out of sight at " + coords(removal.get(removalIndex));
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
            // Taking down its own scaffold: the structure it helped build may hide the block, and leaving it behind
            // is worse, so this breaker does not require line of sight.
            breaker = new BlockBreaker(pos, Scaffold::isScaffold, false, true);
        }
        return State.READY;
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
        if (descentBreaker != null) {
            descentBreaker.stop(new TaskContext(companion, level()));
            descentBreaker = null;
        }
        climbing = false;
        resetPillar();
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

    private CreativeItemLease takeMaterial(TaskContext context) {
        Item item = preferred();
        boolean free = context.canUseCreativeAction(CreativeAction.FREE_MATERIALS);
        if (item == null && free) {
            item = Items.DIRT;
        }
        if (item == null) {
            return null;
        }
        ItemMatcher matcher = ItemMatcher.parse(BuiltInRegistries.ITEM.getKey(item).toString()).orElse(null);
        if (matcher == null) {
            return null;
        }
        List<ItemStack> removed = InventoryOps.remove(companion.inventory(), matcher, 1);
        if (!removed.isEmpty()) {
            return CreativeItemLease.carried(removed.get(0));
        }
        return free ? CreativeItemLease.synthetic(new ItemStack(item)) : null;
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
