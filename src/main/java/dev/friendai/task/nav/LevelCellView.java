package dev.friendai.task.nav;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.mining.BreakProgress;
import dev.friendai.features.navigation.pathing.BlockList;
import dev.friendai.features.navigation.pathing.Cell;
import dev.friendai.features.navigation.pathing.CellView;
import dev.friendai.features.navigation.pathing.CostModel;
import dev.friendai.features.navigation.pathing.PathingConfig;
import dev.friendai.features.navigation.pathing.Pos;
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

    /** Whether a block or the fluid in it would hurt a companion that stood there. */
    public static boolean hazardous(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA) || HAZARDS.contains(state.getBlock());
    }

    /**
     * Whether a block or the fluid in it destroys an item lying in it. Fire, lava and a cactus do; the blocks that
     * only hurt a mob standing there (magma, a berry bush, powder snow, dripstone, a campfire) leave items alone.
     * A drop resting in one of those is still worth taking, and the walking still keeps out of the cell itself.
     */
    public static boolean destroysItems(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CACTUS);
    }

    private final CompanionEntity companion;
    private final ServerLevel level;
    private final PathingConfig config;
    private final CostModel costs;
    private final BlockList breakList;
    private final Map<Long, Cell> cache = new HashMap<>();
    /** What breaking a block costs, per block state: independent of where the state stands. */
    private final Map<BlockState, BreakInfo> breakInfo = new HashMap<>();

    /** Digging and placing off: such a view only classifies terrain, so it never asks the protection rules. */
    private static final PathingConfig TERRAIN_ONLY = PathingConfig.DEFAULTS.withActions(false, false);

    /** The parts of a break time that depend only on the block state and the tools carried. */
    private record BreakInfo(boolean blacklisted, boolean log, float toolSpeed, boolean correctTool) {}

    /**
     * A view that only tells what kind of cell each position is: for checks of the terrain around the companion that
     * never plan a dig or a placement, which skips the protection and tool lookups a search needs.
     */
    public static LevelCellView terrain(CompanionEntity companion) {
        return new LevelCellView(companion, TERRAIN_ONLY, CostModel.DEFAULTS);
    }

    /** Shared per-kind cells for the live view: it only answers kind questions, so these never vary. */
    private static final Cell[] LIVE = new Cell[Cell.Kind.values().length];

    static {
        for (Cell.Kind kind : Cell.Kind.values()) {
            LIVE[kind.ordinal()] = new Cell(kind, -1, false, false);
        }
    }

    /**
     * A view that reads the world on every {@code at} call: for a check that must see a block another entity placed
     * or removed since the step was validated. It keeps no cache and allocates nothing per access, so the executor
     * can use it every tick.
     */
    public static CellView liveTerrain(CompanionEntity companion) {
        return new LiveTerrain(companion);
    }

    private static final class LiveTerrain implements CellView {
        private final ServerLevel level;
        private final AABB bounds;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        LiveTerrain(CompanionEntity companion) {
            this.level = (ServerLevel) companion.level();
            this.bounds = companion.pathBounds();
        }

        @Override
        public Cell at(int x, int y, int z) {
            if (y < level.getMinY() || y > level.getMaxY()) {
                return Cell.UNLOADED;
            }
            if (bounds != null && !bounds.contains(x + 0.5, y + 0.5, z + 0.5)) {
                return Cell.UNLOADED;
            }
            cursor.set(x, y, z);
            if (!level.hasChunkAt(cursor)) {
                return Cell.UNLOADED;
            }
            BlockState state = level.getBlockState(cursor);
            if (state.isAir()) {
                return Cell.AIR;
            }
            VoxelShape shape = state.getCollisionShape(level, cursor);
            return LIVE[kindOf(state, shape, state.getFluidState()).ordinal()];
        }
    }

    public LevelCellView(CompanionEntity companion, PathingConfig config, CostModel costs) {
        this(companion, config, costs, BlockList.parse(config.breakBlacklist(), ignored -> {}));
    }

    public LevelCellView(CompanionEntity companion, PathingConfig config, CostModel costs, BlockList breakList) {
        this.companion = companion;
        this.level = (ServerLevel) companion.level();
        this.config = config;
        this.costs = costs;
        this.breakList = breakList;
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
        if (state.isAir()) {
            return new Cell(Cell.Kind.AIR, -1, false, mayPlaceAt(pos));
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        FluidState fluid = state.getFluidState();
        Cell.Kind kind = kindOf(state, shape, fluid);
        boolean placeable = (kind == Cell.Kind.AIR || kind == Cell.Kind.WATER) && mayPlaceAt(pos);
        return new Cell(kind, breakTicks(pos, state, kind), state.getBlock() instanceof FallingBlock, placeable);
    }

    /** The cell kind of a state, without the break time or placement rules a search needs. */
    private static Cell.Kind kindOf(BlockState state, VoxelShape shape, FluidState fluid) {
        if (shape.isEmpty() && !fluid.isEmpty()) {
            return fluid.is(FluidTags.LAVA) ? Cell.Kind.LAVA : Cell.Kind.WATER;
        }
        if (HAZARDS.contains(state.getBlock())) {
            return Cell.Kind.HAZARD;
        }
        if (isClosedDoor(state)) {
            return Cell.Kind.DOOR;
        }
        if (isClimbable(state)) {
            return Cell.Kind.CLIMBABLE;
        }
        if (shape.isEmpty()) {
            return state.canBeReplaced() ? Cell.Kind.AIR : Cell.Kind.PASSABLE;
        }
        double top = shape.max(Direction.Axis.Y);
        if (top > 1.0) {
            return Cell.Kind.TALL;
        }
        return top < 1.0 ? Cell.Kind.PARTIAL : Cell.Kind.SOLID;
    }

    /** Whether a block may be placed here: allowed by the settings and not protected. */
    private boolean mayPlaceAt(BlockPos pos) {
        return config.allowPlace() && companion.hands().canInteract(pos);
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
        BreakInfo info = breakInfo.computeIfAbsent(state, this::breakInfoOf);
        if (info.blacklisted() || !companion.hands().canInteract(pos)) {
            return -1;
        }
        int ticks = BreakProgress.ticksToBreak(hardness, info.toolSpeed(), info.correctTool());
        if (info.log()) {
            ticks += costs.logPenalty();
        }
        return ticks;
    }

    private BreakInfo breakInfoOf(BlockState state) {
        Set<String> tags = tagsOf(state);
        if (breakList.matches(idOf(state.getBlock()), tags)) {
            return new BreakInfo(true, false, 0f, false);
        }
        ItemStack tool = bestTool(state);
        boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        return new BreakInfo(false, tags.contains("minecraft:logs"), tool.getDestroySpeed(state), correct);
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
        state.getBlock()
                .builtInRegistryHolder()
                .tags()
                .forEach(tag -> tags.add(tag.location().toString()));
        return tags;
    }

    public static String idOf(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }
}
