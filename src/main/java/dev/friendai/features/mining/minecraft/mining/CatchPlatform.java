package dev.friendai.features.mining.minecraft.mining;

import dev.friendai.features.navigation.minecraft.nav.MaterialPicker;
import dev.friendai.features.navigation.minecraft.nav.PlacementLedger;
import dev.friendai.task.TaskContext;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Places the catch cells a valuable drop needs before its block is broken, one cell per tick, and confirms each one
 * landed. Every block goes through {@link dev.friendai.task.interact.CompanionHands#place}, so the ordinary rules
 * apply, and through the task's {@link PlacementLedger}, so the same task can take the platform down afterwards.
 */
public final class CatchPlatform {

    public enum State {
        WORKING,
        READY,
        REFUSED
    }

    /** How long one cell may refuse to appear before the platform is given up. */
    private static final int PLACE_TIMEOUT_TICKS = 20;

    private final List<BlockPos> cells;
    private final PlacementLedger ledger;
    private final MaterialPicker materials;
    private int index;
    private int waited;
    private String failure = "";
    private int placed;

    /** @param cells the cells to fill, in placement order: the cell under the target first, then its rim */
    public CatchPlatform(List<BlockPos> cells, PlacementLedger ledger, MaterialPicker materials) {
        this.cells = List.copyOf(cells);
        this.ledger = ledger;
        this.materials = materials;
    }

    public State tick(TaskContext c) {
        while (index < cells.size()) {
            BlockPos cell = cells.get(index);
            if (holdsBlock(c, cell)) {
                // Something already catches the drop here; nothing to spend.
                index++;
                waited = 0;
                continue;
            }
            if (!c.companion().hands().canInteract(cell)) {
                failure = "the cell is protected";
                return State.REFUSED;
            }
            Anchor anchor = anchor(c, cell);
            if (anchor == null) {
                failure = "nothing to build against";
                return State.REFUSED;
            }
            Optional<ItemStack> taken = materials.takeCheapest(c.companion().inventory());
            if (taken.isEmpty()) {
                failure = "no building material";
                return State.REFUSED;
            }
            c.companion().hands().place(taken.get(), anchor.pos(), anchor.face());
            if (!holdsBlock(c, cell)) {
                if (++waited > PLACE_TIMEOUT_TICKS) {
                    failure = "the placement was refused";
                    return State.REFUSED;
                }
                return State.WORKING;
            }
            ledger.record(cell, taken.get());
            index++;
            waited = 0;
            placed++;
            return State.WORKING;
        }
        return State.READY;
    }

    public void stop() {
        index = cells.size();
    }

    /** The cells this platform is responsible for, so the task can take exactly those down again. */
    public List<BlockPos> cells() {
        return cells;
    }

    public int placed() {
        return placed;
    }

    /** Why the platform stopped, in the summary's words. */
    public String failure() {
        return failure;
    }

    private record Anchor(BlockPos pos, Direction face) {}

    /** A neighbour whose face can be clicked, so vanilla places into {@code cell}. */
    private static @Nullable Anchor anchor(TaskContext c, BlockPos cell) {
        var level = c.level();
        for (Direction side : Direction.values()) {
            BlockPos against = cell.relative(side);
            BlockState state = level.getBlockState(against);
            Direction face = side.getOpposite();
            if (!state.isAir() && state.isFaceSturdy(level, against, face)) {
                return new Anchor(against, face);
            }
        }
        return null;
    }

    private static boolean holdsBlock(TaskContext c, BlockPos cell) {
        BlockState state = c.level().getBlockState(cell);
        return state.getFluidState().isEmpty()
                && !state.getCollisionShape(c.level(), cell).isEmpty();
    }
}
