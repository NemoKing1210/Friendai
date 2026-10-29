package dev.friendai.features.farming.minecraft.task;

import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.farming.minecraft.Crops;
import dev.friendai.features.navigation.minecraft.nav.Approach;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BonemealSource;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Uses bone meal on growing crops around a point until the crop is ripe, the bone meal runs out or count is reached. */
public final class BonemealTask implements Task {
    private final @Nullable BlockPos target;
    private final int radius;
    private final int count;

    private final Set<BlockPos> unreachable = new HashSet<>();
    private final Set<BlockPos> ripened = new HashSet<>();
    private BlockPos center;
    private BlockPos current;
    private Approach approach;
    private int used;
    private String summary = "";
    private boolean outOfBoneMeal;

    public BonemealTask(@Nullable BlockPos target, int radius, int count) {
        this.target = target;
        this.radius = radius;
        this.count = count;
    }

    @Override
    public String type() {
        return "use_bonemeal";
    }

    @Override
    public void start(TaskContext c) {
        if (boneMeal(c.companion()) == 0) {
            summary = "I have no minecraft:bone_meal";
            outOfBoneMeal = true;
            return;
        }
        center = target != null ? target : c.companion().blockPosition().immutable();
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (outOfBoneMeal) {
            return TaskStatus.FAILED;
        }
        if (used >= count) {
            return finish();
        }
        if (current != null
                && (!growing(c.level(), current) || !c.companion().hands().canInteract(current))) {
            if (isCrop(current, c.level())) {
                // It is no longer a valid bone meal target, so it either ripened or vanished.
                if (ripe(c.level(), current)) {
                    ripened.add(current);
                }
            }
            current = null;
            approach = null;
        }
        if (current == null) {
            current = nextGrowing(c);
            if (current == null) {
                return finish();
            }
            approach = new Approach(current);
        }
        Approach.State state = approach.tick(c);
        if (state == Approach.State.MOVING) {
            return TaskStatus.RUNNING;
        }
        if (state == Approach.State.UNREACHABLE) {
            unreachable.add(current);
            current = null;
            approach = null;
            return TaskStatus.RUNNING;
        }
        ItemStack boneMeal = takeOne(c.companion(), Items.BONE_MEAL);
        if (boneMeal.isEmpty()) {
            return finish();
        }
        c.companion().hands().place(boneMeal, current, Direction.UP);
        used++;
        if (!growing(c.level(), current) && ripe(c.level(), current)) {
            ripened.add(current);
            current = null;
            approach = null;
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        if (approach != null) {
            approach.stop(c);
        }
    }

    @Override
    public String describe() {
        return "using bone meal on crops";
    }

    @Override
    public java.util.List<DebugTarget> debugTargets() {
        return current == null
                ? java.util.List.of()
                : java.util.List.of(new DebugTarget(
                        current.getX(), current.getY(), current.getZ(), DebugAction.USE, "Use bone meal"));
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private TaskStatus finish() {
        if (used == 0) {
            summary = "no growing crops within " + radius + " blocks";
            return TaskStatus.FAILED;
        }
        summary = "used " + used + " minecraft:bone_meal; " + ripened.size() + " crops are now ripe";
        return TaskStatus.SUCCESS;
    }

    private @Nullable BlockPos nextGrowing(TaskContext c) {
        if (target != null) {
            return growing(c.level(), target) ? target : null;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        Vec3 at = c.companion().position();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz).immutable();
                    if (unreachable.contains(pos) || ripened.contains(pos) || !growing(c.level(), pos)) {
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

    private static boolean growing(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return Crops.isCrop(state)
                && state.getBlock() instanceof BonemealableBlock bonemealable
                && bonemealable.isValidBonemealTarget(level, pos, state, BonemealSource.INTERACTION);
    }

    private static boolean isCrop(BlockPos pos, ServerLevel level) {
        return Crops.isCrop(level.getBlockState(pos));
    }

    private static boolean ripe(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        return state.getBlock() instanceof NetherWartBlock && state.getValue(NetherWartBlock.AGE) >= 3;
    }

    private static int boneMeal(CompanionEntity companion) {
        return companion.inventory().countItem(Items.BONE_MEAL);
    }

    private static ItemStack takeOne(CompanionEntity companion, net.minecraft.world.item.Item item) {
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
