package dev.friendai.task.mining;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.nav.LevelCellView;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Breaks one block over time: equips the best tool, swings, shows the crack overlay and destroys the block. The drop
 * falls to the ground the way a player's does, so collecting it is the caller's job. The caller decides what each
 * returned state means for its own task.
 */
public final class BreakAction {
    public enum State { WORKING, BROKEN, GONE, BLOCKED }

    private static final int SWING_INTERVAL = 5;

    private final BlockPos pos;
    /** Whether this is a cleanup break of a block placed directly under the companion. */
    private final boolean allowBreakingSupport;
    /** Whether the block is already broken: the action then keeps reporting it, however often it is ticked. */
    private boolean broken;
    private int breakTicks;
    private int breakNeeded;

    public BreakAction(BlockPos pos) {
        this(pos, false);
    }

    public BreakAction(BlockPos pos, boolean allowBreakingSupport) {
        this.pos = pos;
        this.allowBreakingSupport = allowBreakingSupport;
    }

    public BlockPos pos() {
        return pos;
    }

    public State tick(TaskContext c, Predicate<BlockState> stillValid) {
        if (broken) {
            return State.BROKEN;
        }
        var companion = c.companion();
        ServerLevel level = c.level();
        BlockState state = level.getBlockState(pos);
        if (!stillValid.test(state)) {
            stop(c);
            return State.GONE;
        }
        boolean creative = companion.creativeMode();
        boolean blacklisted = creative && TaskSettings.breakList.matches(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), LevelCellView.tagsOf(state));
        if (!companion.hands().canInteract(pos) || (!allowBreakingSupport && occupiesSupport(companion, pos))
                || blacklisted || (!creative && state.getDestroySpeed(level, pos) < 0)) {
            stop(c);
            return State.BLOCKED;
        }
        if (creative) {
            Vec3 center = Vec3.atCenterOf(pos);
            companion.getLookControl().setLookAt(center.x, center.y, center.z);
            if (!companion.hands().canInteract(pos) || (!allowBreakingSupport && occupiesSupport(companion, pos))) {
                stop(c);
                return State.BLOCKED;
            }
            if (!level.destroyBlock(pos, false, companion)) {
                stop(c);
                return level.getBlockState(pos).isAir() ? State.GONE : State.BLOCKED;
            }
            FriendaiServerState.get(level.getServer()).resources().ownBreak(companion, pos);
            broken = true;
            return State.BROKEN;
        }
        if (breakNeeded == 0) {
            ToolSelector.equipBest(companion, state);
            var tool = companion.getMainHandItem();
            boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
            breakNeeded = BreakProgress.ticksToBreak(state.getDestroySpeed(level, pos), tool.getDestroySpeed(state), correct);
        }
        Vec3 center = Vec3.atCenterOf(pos);
        companion.getLookControl().setLookAt(center.x, center.y, center.z);
        if (breakTicks % SWING_INTERVAL == 0) {
            companion.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
        }
        breakTicks++;
        level.destroyBlockProgress(companion.getId(), pos, Math.min(9, breakTicks * 10 / breakNeeded));
        if (breakTicks < breakNeeded) {
            return State.WORKING;
        }
        level.destroyBlockProgress(companion.getId(), pos, -1);
        if (!stillValid.test(level.getBlockState(pos)) || !companion.hands().canInteract(pos)
                || (!allowBreakingSupport && occupiesSupport(companion, pos))) {
            stop(c);
            return State.BLOCKED;
        }
        // Level.destroyBlock drops with an empty tool, so stone would give cobblestone by hand. Drop by hand instead:
        // with the held tool, and nothing when the block needs a tool the companion does not hold.
        var heldTool = companion.getMainHandItem();
        BlockState brokenState = level.getBlockState(pos);
        var blockEntity = level.getBlockEntity(pos);
        boolean dropsLoot = !brokenState.requiresCorrectToolForDrops() || heldTool.isCorrectToolForDrops(brokenState);
        if (!level.destroyBlock(pos, false, companion)) {
            stop(c);
            return level.getBlockState(pos).isAir() ? State.GONE : State.BLOCKED;
        }
        if (dropsLoot) {
            Block.dropResources(brokenState, level, pos, blockEntity, companion, heldTool);
        }
        FriendaiServerState.get(level.getServer()).resources().ownBreak(companion, pos);
        companion.hunger().addExhaustion(CompanionEntity.EXHAUSTION_BLOCK);
        broken = true;
        return State.BROKEN;
    }

    /** Clears the crack overlay; safe to call at any time. */
    public void stop(TaskContext c) {
        c.level().destroyBlockProgress(c.companion().getId(), pos, -1);
    }

    private static boolean occupiesSupport(CompanionEntity companion, BlockPos pos) {
        AABB bounds = companion.getBoundingBox();
        double top = pos.getY() + 1.0;
        return Math.abs(bounds.minY - top) < 0.2
                && bounds.maxX > pos.getX() && bounds.minX < pos.getX() + 1.0
                && bounds.maxZ > pos.getZ() && bounds.minZ < pos.getZ() + 1.0;
    }
}
