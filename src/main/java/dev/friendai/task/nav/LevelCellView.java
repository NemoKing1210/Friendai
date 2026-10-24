package dev.friendai.task.nav;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.pathing.Cell;
import dev.friendai.pathing.CellView;
import dev.friendai.pathing.CostModel;
import dev.friendai.pathing.PathingConfig;
import dev.friendai.pathing.Pos;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.mining.BreakProgress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The world as the pathing core sees it, for one companion: block states classified into cells, break times taken
 * with the best tool it carries, and the config's blacklists and protection rules applied.
 *
 * <p>One view per search. Results are cached for its lifetime, so the view must not outlive the search that reads it.
 */
public final class LevelCellView implements CellView {

    private static final Set<Block> HAZARDS = Set.of(
            Blocks.FIRE,
            Blocks.SOUL_FIRE,
            Blocks.MAGMA_BLOCK,
            Blocks.CACTUS,
            Blocks.SWEET_BERRY_BUSH,
            Blocks.POWDER_SNOW,
            Blocks.POINTED_DRIPSTONE,
            Blocks.CAMPFIRE,
            Blocks.SOUL_CAMPFIRE);

    private final CompanionEntity companion;
    private final ServerLevel level;
    private final PathingConfig config;
    private final CostModel costs;
    private final Map<Long, Cell> cache = new HashMap<>();

    public LevelCellView(CompanionEntity companion, PathingConfig config, CostModel costs) {
        this.companion = companion;
        this.level = (ServerLevel) companion.level();
        this.config = config;
        this.costs = costs;
    }

    @Override
    public Cell at(int x, int y, int z) {
        long key = Pos.pack(x, y, z);
        Cell cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Cell cell = classify(new BlockPos(x, y, z));
        cache.put(key, cell);
        return cell;
    }

    private Cell classify(BlockPos pos) {
        if (pos.getY() < level.getMinY() || pos.getY() > level.getMaxY() || !level.hasChunkAt(pos)) {
            return Cell.UNLOADED;
        }
        AABB bounds = companion.pathBounds();
        if (bounds != null && !bounds.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
            return Cell.UNLOADED;
        }
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos);
        FluidState fluid = state.getFluidState();
        Cell.Kind kind;
        if (shape.isEmpty() && !fluid.isEmpty()) {
            kind = fluid.is(FluidTags.LAVA) ? Cell.Kind.LAVA : Cell.Kind.WATER;
        } else if (state.isAir()) {
            kind = Cell.Kind.AIR;
        } else if (HAZARDS.contains(state.getBlock())) {
            kind = Cell.Kind.HAZARD;
        } else if (isClosedDoor(state)) {
            kind = Cell.Kind.DOOR;
        } else if (isClimbable(state)) {
            kind = Cell.Kind.CLIMBABLE;
        } else if (shape.isEmpty()) {
            kind = state.canBeReplaced() ? Cell.Kind.AIR : Cell.Kind.PASSABLE;
        } else {
            double top = shape.max(Direction.Axis.Y);
            if (top > 1.0) {
                kind = Cell.Kind.TALL;
            } else if (top < 1.0) {
                kind = Cell.Kind.PARTIAL;
            } else {
                kind = Cell.Kind.SOLID;
            }
        }
        boolean placeable = config.allowPlace() && companion.hands().canInteract(pos)
                && (kind == Cell.Kind.AIR || kind == Cell.Kind.WATER || kind == Cell.Kind.PASSABLE);
        return new Cell(kind, breakTicks(pos, state, kind), state.getBlock() instanceof FallingBlock, placeable);
    }

    private static boolean isClosedDoor(BlockState state) {
        if (!state.is(BlockTags.WOODEN_DOORS) && !state.is(BlockTags.FENCE_GATES)) {
            return false;
        }
        return !state.hasProperty(BlockStateProperties.OPEN) || !state.getValue(BlockStateProperties.OPEN);
    }

    private static boolean isClimbable(BlockState state) {
        return state.is(BlockTags.CLIMBABLE) || state.getBlock() instanceof ScaffoldingBlock;
    }

    private int breakTicks(BlockPos pos, BlockState state, Cell.Kind kind) {
        if (!breakableKind(kind) || !config.allowBreak()) {
            return -1;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) {
            return -1;
        }
        if (!companion.hands().canInteract(pos)) {
            return -1;
        }
        Set<String> tags = tagsOf(state);
        if (TaskSettings.breakList.matches(idOf(state.getBlock()), tags)) {
            return -1;
        }
        ItemStack tool = bestTool(state);
        boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        int ticks = BreakProgress.ticksToBreak(hardness, tool.getDestroySpeed(state), correct);
        if (tags.contains("minecraft:logs")) {
            ticks += costs.logPenalty();
        }
        return ticks;
    }

    private static boolean breakableKind(Cell.Kind kind) {
        return kind == Cell.Kind.SOLID
                || kind == Cell.Kind.PARTIAL
                || kind == Cell.Kind.TALL
                || kind == Cell.Kind.CLIMBABLE
                || kind == Cell.Kind.DOOR
                || kind == Cell.Kind.PASSABLE;
    }

    /** The fastest stack the companion carries for this block, without touching the inventory. */
    private ItemStack bestTool(BlockState state) {
        ItemStack best = companion.getMainHandItem();
        float speed = best.getDestroySpeed(state);
        var inventory = companion.inventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            float candidate = stack.getDestroySpeed(state);
            if (candidate > speed) {
                speed = candidate;
                best = stack;
            }
        }
        return best;
    }

    public static Set<String> tagsOf(BlockState state) {
        Set<String> tags = new HashSet<>();
        state.getBlock().builtInRegistryHolder().tags().forEach(tag -> tags.add(tag.location().toString()));
        return tags;
    }

    public static String idOf(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }
}
