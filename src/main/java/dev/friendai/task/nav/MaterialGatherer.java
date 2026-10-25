package dev.friendai.task.nav;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.pathing.CostModel;
import dev.friendai.pathing.GoalReach;
import dev.friendai.pathing.Pos;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.inventory.DropSettle;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.mining.BlockSearch;
import dev.friendai.task.mining.BreakAction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Gathers the cheap natural blocks a route needs: walks to the nearest few, breaks them and waits for their drops to
 * come to rest at its feet.
 *
 * <p>Only blocks whose drops the companion can place are candidates, and stone only when it carries a correct pickaxe
 * (otherwise breaking it drops nothing). A block nothing can reach is dropped one at a time, so one buried candidate
 * does not end the gathering.
 */
public final class MaterialGatherer {
    public enum State { WORKING, DONE }

    /** How far from the companion candidates are looked for. */
    private static final int RADIUS = 16;
    /** How many candidates one walk approaches; more than can be needed in one round. */
    private static final int CANDIDATES = 8;
    private static final double REACH = Approach.REACH;
    /** How long one gathered block's drop gets to land and leave its pickup delay. */
    private static final int SETTLE_TICKS = 12;

    private static final BlockMatcher DIRT = BlockMatcher.parse("#minecraft:dirt").orElseThrow();
    private static final BlockMatcher GRASS = BlockMatcher.parse("minecraft:grass_block").orElseThrow();
    private static final BlockMatcher NETHERRACK = BlockMatcher.parse("minecraft:netherrack").orElseThrow();
    private static final BlockMatcher STONE = BlockMatcher.parse("#minecraft:base_stone_overworld").orElseThrow();
    private static final BlockMatcher NETHER_STONE = BlockMatcher.parse("#minecraft:base_stone_nether").orElseThrow();

    private final int needed;
    /** Cells the planned route walks or builds through, which must not be taken away from it. */
    private final Set<BlockPos> avoid;
    private final MaterialPicker picker = new MaterialPicker(TaskSettings.pathing);
    private final Set<BlockPos> blacklist = new HashSet<>();

    private List<BlockPos> candidates = List.of();
    private Navigator navigator;
    private BreakAction action;
    private DropSettle settle;
    private int haveAtStart = -1;
    private int gathered;
    private boolean noticed;

    public MaterialGatherer(int needed) {
        this(needed, Set.of());
    }

    /**
     * @param avoid block cells the route itself needs; they are skipped as candidates
     */
    public MaterialGatherer(int needed, Set<BlockPos> avoid) {
        this.needed = needed;
        this.avoid = Set.copyOf(avoid);
    }

    /** How many blocks were broken so far; the inventory count is the authority, this is the fallback. */
    public int gathered() {
        return gathered;
    }

    public State tick(TaskContext c) {
        CompanionEntity companion = c.companion();
        if (!TaskSettings.pathing.allowBreak()) {
            // Gathering is digging; a server that forbids breaking forbids this too.
            return finish(c);
        }
        if (haveAtStart < 0) {
            haveAtStart = picker.count(companion.inventory());
        }
        if (settle != null) {
            // The block just broken drops where it stood: wait for the delay to run out and take it, so the
            // inventory count below is the truth before the navigator asks for material again.
            if (!settle.tick(c)) {
                return State.WORKING;
            }
            settle = null;
        }
        if (enough(companion)) {
            return finish(c);
        }
        if (action != null) {
            return tickBreak(c, companion);
        }
        if (navigator != null) {
            return tickWalk(c, companion);
        }
        if (candidates.isEmpty()) {
            candidates = findCandidates(c, companion);
            if (!candidates.isEmpty() && !noticed) {
                noticed = true;
                companion.taskRunner().notice("gathering " + label(c, candidates.get(0))
                        + " to build a bridge");
            }
        }
        if (candidates.isEmpty()) {
            // Nothing left to try: the search decides what that means.
            return finish(c);
        }
        navigator = new Navigator(companion, false);
        navigator.setGoal(goal(companion));
        return State.WORKING;
    }

    /** Stops navigation and clears the crack overlay; safe to call at any time. */
    public void stop(TaskContext c) {
        if (action != null) {
            action.stop(c);
            action = null;
        }
        if (navigator != null) {
            navigator.stop(c);
            navigator = null;
        }
        settle = null;
    }

    private State tickWalk(TaskContext c, CompanionEntity companion) {
        switch (navigator.tick(c)) {
            case ARRIVED -> {
                navigator.stop(c);
                navigator = null;
                BlockPos near = nearestWithinReach(companion);
                if (near != null && companion.hands().canInteract(near)) {
                    candidates = without(candidates, near);
                    action = new BreakAction(near);
                } else {
                    // The route ended with nothing in reach: give up on the closest and try another.
                    dropNearest(companion);
                }
            }
            case UNREACHABLE -> {
                navigator.stop(c);
                navigator = null;
                dropNearest(companion);
            }
            default -> {
                // MOVING, SEARCHING (and GATHERING, which a nested navigator never reports): keep working.
            }
        }
        return State.WORKING;
    }

    private State tickBreak(TaskContext c, CompanionEntity companion) {
        switch (action.tick(c, state -> !state.isAir() && dropsMaterial(state, companion))) {
            case WORKING -> {
                return State.WORKING;
            }
            case BROKEN -> {
                gathered++;
                settle = new DropSettle(SETTLE_TICKS, DropSettle.INTERACT_RADIUS, null);
            }
            case GONE -> {
                // Something else removed it; the inventory growth decides whether it counted.
            }
        }
        action = null;
        return State.WORKING;
    }

    private boolean enough(CompanionEntity companion) {
        if (gathered >= needed) {
            return true;
        }
        return haveAtStart >= 0 && picker.count(companion.inventory()) - haveAtStart >= needed;
    }

    private State finish(TaskContext c) {
        stop(c);
        return State.DONE;
    }

    private void dropNearest(CompanionEntity companion) {
        BlockPos drop = nearest(companion);
        if (drop == null) {
            return;
        }
        blacklist.add(drop);
        candidates = without(candidates, drop);
    }

    private List<BlockPos> findCandidates(TaskContext c, CompanionEntity companion) {
        ServerLevel level = c.level();
        BlockMatcher matcher = new BlockMatcher() {
            @Override
            public boolean test(BlockState state) {
                return dropsMaterial(state, companion);
            }

            @Override
            public String label() {
                return "path material";
            }
        };
        List<BlockPos> found = BlockSearch.nearestN(
                level, companion.blockPosition(), RADIUS, matcher, blacklist, CANDIDATES);
        AABB bounds = companion.pathBounds();
        List<BlockPos> result = new ArrayList<>(found.size());
        for (BlockPos pos : found) {
            if (avoid.contains(pos) || !companion.hands().canInteract(pos)) {
                // Protected ground, like a spawn region: the block stays where it is.
                continue;
            }
            if (bounds != null && !bounds.contains(Vec3.atCenterOf(pos))) {
                continue;
            }
            result.add(pos);
        }
        return result;
    }

    /** Whether breaking {@code state} yields a block the companion can bridge with. */
    private static boolean dropsMaterial(BlockState state, CompanionEntity companion) {
        if (state.isAir()) {
            return false;
        }
        if (TaskSettings.breakList.matches(LevelCellView.idOf(state.getBlock()), LevelCellView.tagsOf(state))) {
            return false;
        }
        if (DIRT.test(state) || GRASS.test(state) || NETHERRACK.test(state)) {
            return true;
        }
        if (!STONE.test(state) && !NETHER_STONE.test(state)) {
            return false;
        }
        // Stone and deepslate drop nothing without a pickaxe.
        return holdsCorrectTool(companion, state);
    }

    private static boolean holdsCorrectTool(CompanionEntity companion, BlockState state) {
        if (companion.getMainHandItem().isCorrectToolForDrops(state)) {
            return true;
        }
        SimpleContainer inventory = companion.inventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return false;
    }

    private GoalReach goal(CompanionEntity companion) {
        List<Pos> targets = new ArrayList<>(candidates.size());
        for (BlockPos pos : candidates) {
            targets.add(new Pos(pos.getX(), pos.getY(), pos.getZ()));
        }
        return new GoalReach(targets, REACH, companion.getEyeHeight(), CostModel.DEFAULTS.walk());
    }

    private BlockPos nearestWithinReach(CompanionEntity companion) {
        Vec3 eye = companion.getEyePosition();
        BlockPos best = null;
        double bestDistance = REACH;
        for (BlockPos candidate : candidates) {
            double distance = eye.distanceTo(Vec3.atCenterOf(candidate));
            if (distance <= bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private BlockPos nearest(CompanionEntity companion) {
        Vec3 eye = companion.getEyePosition();
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos candidate : candidates) {
            double distance = eye.distanceTo(Vec3.atCenterOf(candidate));
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static List<BlockPos> without(List<BlockPos> positions, BlockPos remove) {
        List<BlockPos> result = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            if (!pos.equals(remove)) {
                result.add(pos);
            }
        }
        return result;
    }

    private static String label(TaskContext c, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(c.level().getBlockState(pos).getBlock()).getPath();
    }
}
