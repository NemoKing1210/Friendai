package dev.friendai.task.mining;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.mining.MiningBounds;
import dev.friendai.mining.MiningSafety;
import dev.friendai.pathing.Cell;
import dev.friendai.pathing.CellView;
import dev.friendai.pathing.CostModel;
import dev.friendai.pathing.PathStep;
import dev.friendai.pathing.PathingConfig;
import dev.friendai.pathing.Pos;
import dev.friendai.task.TaskContext;
import dev.friendai.task.nav.Approach;
import dev.friendai.task.nav.LevelCellView;
import dev.friendai.task.nav.PathExecutionGuard;
import dev.friendai.task.nav.Sight;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Fresh, mining-scoped world checks for proposed excavation and movement. */
public final class ExcavationGuard implements PathExecutionGuard {

    private static final int[][] NEIGHBORS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private final MiningBounds bounds;
    private final Set<Pos> protectedSupports;

    public ExcavationGuard(MiningBounds bounds, Set<Pos> protectedSupports) {
        this.bounds = bounds;
        this.protectedSupports = Set.copyOf(protectedSupports);
    }

    @Override
    public boolean allowBreak(TaskContext context, PathStep step, Pos pos) {
        CompanionEntity companion = context.companion();
        BlockPos blockPos = minecraft(pos);
        if (!bounds.contains(pos)
                || protectedSupports.contains(pos)
                || supportsFootprint(companion, blockPos)
                || !context.level().hasChunkAt(blockPos)
                || !companion.hands().canInteract(blockPos)) {
            return false;
        }
        BlockState state = context.level().getBlockState(blockPos);
        if (state.isAir()
                || state.getDestroySpeed(context.level(), blockPos) < 0
                || unsafeExposure(context.level(), blockPos)) {
            return false;
        }
        return new LevelCellView(
                        companion,
                        context.serverSettings().pathing(),
                        CostModel.DEFAULTS,
                        context.serverSettings().breakList())
                .at(pos)
                .breakable();
    }

    @Override
    public boolean allowMove(TaskContext context, PathStep step) {
        Pos from = step.from().pos();
        Pos to = step.to().pos();
        if (!bounds.contains(from) || !bounds.contains(to)) {
            return false;
        }
        CellView view = planningView(context, context.serverSettings().pathing());
        PathStep alreadyExcavated = new PathStep(
                step.from(),
                step.to(),
                step.move(),
                java.util.List.of(),
                java.util.List.of(),
                step.cost(),
                step.headUnderwater());
        return MiningSafety.reversible(view, alreadyExcavated);
    }

    /** Safe for a single explicitly requested resource, even when pathing blacklists its block tag. */
    public boolean allowTarget(TaskContext context, BlockPos target) {
        CompanionEntity companion = context.companion();
        ServerLevel level = context.level();
        Pos targetPos = plain(target);
        BlockState state = level.getBlockState(target);
        if (!bounds.contains(targetPos)
                || state.isAir()
                || state.getDestroySpeed(level, target) < 0
                || !level.hasChunkAt(target)
                || !companion.hands().canInteract(target)
                || supportsFootprint(companion, target)) {
            return false;
        }
        Vec3 eye = companion.getEyePosition();
        if (eye.distanceTo(Vec3.atCenterOf(target)) > Approach.REACH || !Sight.canSee(level, companion, target)) {
            return false;
        }
        if (state.requiresCorrectToolForDrops() && !hasCorrectTool(companion, state)) {
            return false;
        }
        CellView view = planningView(context, context.serverSettings().pathing());
        var block = companion.blockPosition();
        var stance = new dev.friendai.pathing.Node(block.getX(), block.getY(), block.getZ());
        return MiningSafety.canBreak(view, stance, targetPos, protectedSupports);
    }

    /** Localized explanation for a refused explicit target. */
    public UserMessage targetFailure(TaskContext context, BlockPos target) {
        CompanionEntity companion = context.companion();
        BlockState state = context.level().getBlockState(target);
        if (state.requiresCorrectToolForDrops() && !hasCorrectTool(companion, state)) {
            return UserMessage.of("friendai.mining.no_tool");
        }
        Pos pos = plain(target);
        if (!bounds.contains(pos) || supportsFootprint(companion, target) || protectedSupports.contains(pos)) {
            return UserMessage.of("friendai.mining.unsafe_support");
        }
        if (unsafeExposure(context.level(), target)) {
            return UserMessage.of("friendai.mining.unsafe_passage");
        }
        return UserMessage.of("friendai.mining.unreachable");
    }

    /** A mining-specific fresh view that treats waterlogged cells as water without changing normal navigation. */
    public CellView planningView(TaskContext context, PathingConfig config) {
        CompanionEntity companion = context.companion();
        ServerLevel level = context.level();
        CellView terrain = new LevelCellView(
                companion, config, CostModel.DEFAULTS, context.serverSettings().breakList());
        return (x, y, z) -> {
            BlockPos pos = new BlockPos(x, y, z);
            Cell cell = terrain.at(x, y, z);
            if (!level.hasChunkAt(pos)) {
                return cell;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getFluidState().is(FluidTags.WATER)) {
                return new Cell(Cell.Kind.WATER, cell.breakTicks(), false, cell.placeable());
            }
            return cell;
        };
    }

    private static boolean unsafeExposure(ServerLevel level, BlockPos pos) {
        for (int[] d : NEIGHBORS) {
            BlockPos neighbor = pos.offset(d[0], d[1], d[2]);
            if (!level.hasChunkAt(neighbor)) {
                return true;
            }
            BlockState state = level.getBlockState(neighbor);
            if (state.getFluidState().is(FluidTags.WATER)
                    || state.getFluidState().is(FluidTags.LAVA)
                    || state.getBlock() instanceof FallingBlock) {
                return true;
            }
        }
        return false;
    }

    private static boolean supportsFootprint(CompanionEntity companion, BlockPos pos) {
        AABB box = companion.getBoundingBox();
        return Math.abs(box.minY - (pos.getY() + 1.0)) < 0.2
                && box.maxX > pos.getX()
                && box.minX < pos.getX() + 1.0
                && box.maxZ > pos.getZ()
                && box.minZ < pos.getZ() + 1.0;
    }

    private static boolean hasCorrectTool(CompanionEntity companion, BlockState state) {
        if (companion.getMainHandItem().isCorrectToolForDrops(state)) {
            return true;
        }
        SimpleContainer inventory = companion.inventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos minecraft(Pos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    private static Pos plain(BlockPos pos) {
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }
}
