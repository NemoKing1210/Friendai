package dev.friendai.features.mining.minecraft.mining;

import dev.friendai.features.mining.DropRisk;
import dev.friendai.features.navigation.minecraft.nav.LevelCellView;
import dev.friendai.features.navigation.minecraft.nav.MaterialPicker;
import dev.friendai.features.navigation.minecraft.nav.PlacementLedger;
import dev.friendai.features.navigation.pathing.Cell;
import dev.friendai.features.navigation.pathing.CellView;
import dev.friendai.features.navigation.pathing.CostModel;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.task.TaskContext;
import dev.friendai.task.autonomy.PlaceGuard;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The one decision every mining path asks before the first hit: may this block be broken without losing its drop?
 *
 * <p>A block whose drop is not valuable is never slowed down. A valuable block over something that catches the drop
 * is broken as before. A valuable block whose drop would fall into lava, a ravine or the void gets a catch platform
 * built first, or a typed refusal when no platform can be built — never a silent gamble.
 */
public final class DropGuard {

    /** What to do with one target: nothing, build these cells, or say why not. */
    public record Plan(CatchPlatform platform, MiningRefusal refusal) {
        public static Plan clear() {
            return new Plan(null, null);
        }

        public static Plan build(CatchPlatform platform) {
            return new Plan(platform, null);
        }

        public static Plan refuse(MiningRefusal refusal) {
            return new Plan(null, refusal);
        }

        public boolean isClear() {
            return platform == null && refusal == null;
        }
    }

    private DropGuard() {}

    /**
     * @param ledger the task's own ledger, so the platform is taken down with the rest of what it placed
     */
    public static Plan plan(TaskContext c, BlockPos target, PlacementLedger ledger) {
        if (!c.mining().protectDrops()) {
            return Plan.clear();
        }
        BlockState state = c.level().getBlockState(target);
        if (state.isAir() || !c.mining().protects(blockId(state), LevelCellView.tagsOf(state))) {
            return Plan.clear();
        }
        CellView view = view(c);
        Pos at = new Pos(target.getX(), target.getY(), target.getZ());
        DropRisk.Report report =
                DropRisk.assess(view, at, pos -> mayBuild(c, pos), c.mining().catchBlocks());
        return switch (report.verdict()) {
            case SAFE -> Plan.clear();
            case IMPOSSIBLE ->
                Plan.refuse(new MiningRefusal.DropUnprotected(target, report.landing(), report.reason(), ""));
            case NEEDS_PLATFORM -> {
                List<BlockPos> cells = new ArrayList<>(report.catchCells().size());
                for (Pos cell : report.catchCells()) {
                    cells.add(new BlockPos(cell.x(), cell.y(), cell.z()));
                }
                yield Plan.build(new CatchPlatform(cells, ledger, materials(c)));
            }
        };
    }

    private static boolean mayBuild(TaskContext c, Pos pos) {
        BlockPos at = new BlockPos(pos.x(), pos.y(), pos.z());
        return c.pathingConfig().allowPlace() && c.companion().hands().canInteract(at) && !PlaceGuard.protects(c, at);
    }

    private static CellView view(TaskContext c) {
        var level = c.level();
        CellView terrain = new LevelCellView(c.companion(), c.pathingConfig(), CostModel.DEFAULTS, c.breakList());
        return (x, y, z) -> level.hasChunkAt(new BlockPos(x, y, z)) ? terrain.at(x, y, z) : Cell.UNLOADED;
    }

    private static MaterialPicker materials(TaskContext c) {
        return new MaterialPicker(c.pathingConfig(), c.breakList(), c.placeList());
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
