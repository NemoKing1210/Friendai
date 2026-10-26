package dev.friendai.task.farming;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.farming.CellKind;
import dev.friendai.farming.FarmSnapshot;
import dev.friendai.farming.FieldCell;
import dev.friendai.farming.FoodNeed;
import dev.friendai.farming.Soil;
import dev.friendai.task.TaskContext;
import dev.friendai.task.combat.Food;
import dev.friendai.task.interact.ContainerAccess;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

/** Reads the world around a farm into the pure planner's {@link FarmSnapshot}. Server thread only. */
public final class FarmSnapshotBuilder {
    private static final int WATER_RADIUS = 4;
    private static final int CHEST_RADIUS = 16;

    private FarmSnapshotBuilder() {}

    /** Scans the disc around the centre (dy -2..2), skipping the excluded positions. */
    public static FarmSnapshot build(TaskContext c, BlockPos center, int radius, Set<BlockPos> exclude) {
        ServerLevel level = c.level();
        CompanionEntity companion = c.companion();
        List<FieldCell> cells = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz).immutable();
                    if (exclude.contains(pos)) {
                        continue;
                    }
                    FieldCell cell = classify(level, pos, dx, dy, dz);
                    if (cell != null) {
                        cells.add(cell);
                    }
                }
            }
        }
        Map<String, Integer> inventory = new HashMap<>();
        boolean carriesFood = false;
        int boneMeal = 0;
        boolean hasHoe = companion.getMainHandItem().is(ItemTags.HOES);
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            inventory.merge(CropIndex.id(stack.getItem()), stack.getCount(), Integer::sum);
            hasHoe |= stack.is(ItemTags.HOES);
            // Carrots and potatoes in the bag are planting stock, not a meal the companion counts on.
            carriesFood |= !Crops.isPlantableSeed(stack.getItem()) && Food.isSafe(companion, stack, false);
            if (stack.is(Items.BONE_MEAL)) {
                boneMeal += stack.getCount();
            }
        }
        boolean chestSpace = ContainerAccess.nearest(level, center, CHEST_RADIUS, container -> {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (container.getItem(slot).isEmpty()) {
                    return true;
                }
            }
            return false;
        }).isPresent();
        FoodNeed need = FoodNeed.of(companion.hunger().food(), carriesFood);
        return new FarmSnapshot(cells, inventory, hasHoe, boneMeal, chestSpace, need);
    }

    private static FieldCell classify(ServerLevel level, BlockPos pos, int dx, int dy, int dz) {
        BlockState state = level.getBlockState(pos);
        Optional<Harvestable> ripe = Crops.harvestable(level, pos);
        if (ripe.isPresent()) {
            Harvestable harvestable = ripe.get();
            boolean replants = harvestable.kind() == Harvestable.Kind.CROP
                    || harvestable.kind() == Harvestable.Kind.NETHER_WART;
            String seed = replants && harvestable.replant() != null ? CropIndex.id(harvestable.replant()) : null;
            return new FieldCell(dx, dy, dz, CellKind.RIPE, CropIndex.blockId(state.getBlock()), seed,
                    ageOf(state), null);
        }
        if (state.getBlock() instanceof CropBlock || state.getBlock() instanceof NetherWartBlock) {
            return new FieldCell(dx, dy, dz, CellKind.GROWING, CropIndex.blockId(state.getBlock()), null,
                    ageOf(state), null);
        }
        boolean airAbove = level.getBlockState(pos.above()).isAir();
        if (airAbove && state.is(Blocks.FARMLAND)) {
            return new FieldCell(dx, dy, dz, CellKind.EMPTY_SOIL, null, null, 0, Soil.FARMLAND);
        }
        if (airAbove && state.is(Blocks.SOUL_SAND)) {
            return new FieldCell(dx, dy, dz, CellKind.EMPTY_SOIL, null, null, 0, Soil.SOUL_SAND);
        }
        if (isTillCandidate(level, pos)) {
            return new FieldCell(dx, dy, dz, CellKind.TILLABLE, null, null, 0, null);
        }
        return null;
    }

    private static int ageOf(BlockState state) {
        // CropBlock keeps its age property protected; every crop names it "age".
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty age && age.getName().equals("age")) {
                return state.getValue(age);
            }
        }
        return 0;
    }

    /** Dirt-like ground with air above and water within {@value #WATER_RADIUS} blocks on its layer or the one below. */
    public static boolean isTillCandidate(ServerLevel level, BlockPos pos) {
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
}
