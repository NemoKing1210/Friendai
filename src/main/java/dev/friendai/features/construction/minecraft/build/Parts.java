package dev.friendai.features.construction.minecraft.build;

import dev.friendai.features.construction.blueprint.PlacementRefusal;
import dev.friendai.foundation.places.Place;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.task.TaskContext;
import dev.friendai.task.autonomy.PlaceGuard;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Pieces of a blueprint that the game places on its own: a door's upper half comes with the lower one, and a bed
 * always arrives as a pair.
 *
 * <p>The blueprint names one of the two cells, and the game orients the other one by the way the companion happened
 * to face when it placed the block, not by what the blueprint says. Reconciling moves the partner to the cell the
 * blueprint asks for and gives it the blueprint's state, removing the one the placement left behind.
 *
 * <p>Reconciliation is one verified placement, not a blind write. The actual partner is derived from the state of the
 * half being fixed, so an unrelated bed beside it is never mistaken for the other half. Every affected cell is checked
 * before the first mutation — the chunk is loaded, the caller's {@link CellGuard} permits it, and the cell is either
 * empty, replaceable, or this pair's own half — and every write is read back afterwards. A cell that cannot be fixed
 * yields a {@link PlacementRefusal} and leaves the world as it was, or reports the cells already changed when the
 * failure happened after them.
 *
 * <p>The cells are written without neighbour updates on purpose: the pair is briefly inconsistent between the two
 * writes, and a neighbour update in that moment would make the game break the block it had just placed.
 */
public final class Parts {
    /**
     * Properties a player's interaction changes without changing the structure: a mob opens a door, a sleeper
     * occupies a bed, water fills a cell. They are never the difference between a correct pair and a broken one.
     */
    private static final Set<String> VOLATILE_PROPERTIES = Set.of("open", "powered", "occupied", "waterlogged");

    private Parts() {}

    /** Whether the caller may write one affected cell; empty when it may. */
    @FunctionalInterface
    public interface CellGuard {
        /** A caller without protection: the build's own site checks already ran. */
        CellGuard ALLOW = pos -> Optional.empty();

        Optional<PlacementRefusal> refusalAt(BlockPos pos);
    }

    /** Why one affected cell cannot be fixed: another block, an unloaded chunk or a protected cell. */
    public record Refusal(BlockPos pos, PlacementRefusal reason) {}

    /** The result of one reconciliation: the cells it changed and, when it stopped, the cell that refused it. */
    public record Outcome(Set<BlockPos> changed, Refusal refused) {
        public Outcome {
            changed = Set.copyOf(changed);
        }

        /** Every affected cell was written and read back as the blueprint state. */
        public boolean done() {
            return refused == null;
        }
    }

    /** The server's protection and the owner's autonomous places, through the same boundary every placement uses. */
    public static CellGuard guarding(TaskContext context) {
        return pos -> {
            if (!context.companion().hands().canInteract(pos)) {
                return Optional.of(new PlacementRefusal.Protected(toPos(pos), "", "", 0, 0, 0, 0));
            }
            Place place = PlaceGuard.protecting(context, pos);
            return place == null ? Optional.empty() : Optional.of(protectedRefusal(toPos(pos), place));
        };
    }

    /** The typed refusal for a named place, matching the build's own protection report. */
    public static PlacementRefusal protectedRefusal(Pos pos, Place place) {
        return new PlacementRefusal.Protected(
                pos,
                place.name(),
                place.kind().name().toLowerCase(Locale.ROOT),
                place.radius(),
                place.anchor().x(),
                place.anchor().z(),
                0);
    }

    /** True when the block is placed as a pair, so its partner has to be checked too. */
    public static boolean isPair(BlockState state) {
        return state.getBlock() instanceof DoorBlock || state.getBlock() instanceof AbstractBedBlock;
    }

    /** True when this half is the one that carries the pair's state: a door's lower half or a bed's foot. */
    public static boolean isPairSource(BlockState state) {
        if (state.getBlock() instanceof DoorBlock) {
            return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
        }
        return state.getBlock() instanceof AbstractBedBlock && state.getValue(AbstractBedBlock.PART) == BedPart.FOOT;
    }

    /**
     * True when the cell holds the wanted state and, for a pair, every half is present and correct. Read-only: an
     * unloaded partner chunk counts as incomplete, because the pair cannot be proven.
     */
    public static boolean isComplete(ServerLevel level, BlockPos pos, BlockState wanted) {
        if (!structurallyEqual(level.getBlockState(pos), wanted)) {
            return false;
        }
        if (!isPair(wanted)) {
            return true;
        }
        BlockPos partner = partnerOf(pos, wanted);
        if (!readable(level, partner)) {
            return false;
        }
        return structurallyEqual(level.getBlockState(partner), partnerWanted(wanted));
    }

    /**
     * True when two states describe the same block and structure, ignoring the properties an interaction changes.
     * Used both by the pair check and by the write-back verification, so an opened door still counts as placed.
     */
    public static boolean structurallyEqual(BlockState actual, BlockState expected) {
        if (actual.getBlock() != expected.getBlock()) {
            return false;
        }
        for (Property<?> property : expected.getProperties()) {
            if (VOLATILE_PROPERTIES.contains(property.getName())) {
                continue;
            }
            if (!actual.hasProperty(property)
                    || !Objects.equals(actual.getValue(property), expected.getValue(property))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Makes the cell at {@code pos} match {@code wanted} and brings its partner in line, verifying both. The cell may be
     * named by either half: a door's upper half repairs its lower one and a bed's head repairs its foot, so the source
     * of the pair is always the cell that carries the state the blueprint describes.
     */
    public static Outcome reconcile(ServerLevel level, BlockPos pos, BlockState wanted, CellGuard guard) {
        if (!isPair(wanted)) {
            return writeSingle(level, pos, wanted, guard);
        }
        BlockPos source = sourceOf(pos, wanted);
        BlockState sourceWanted = sourceWanted(wanted);
        if (level.getBlockState(source).getBlock() != sourceWanted.getBlock()) {
            // The cell that carries the pair is not this block, so the pair cannot be repaired from here.
            return new Outcome(
                    Set.of(),
                    new Refusal(
                            source,
                            new PlacementRefusal.Rejected(
                                    toPos(source), describe(sourceWanted), blockId(level.getBlockState(source)), 0)));
        }
        return wanted.getBlock() instanceof DoorBlock
                ? reconcileDoor(level, source, sourceWanted, guard)
                : reconcileBed(level, source, sourceWanted, guard);
    }

    private static Outcome writeSingle(ServerLevel level, BlockPos pos, BlockState wanted, CellGuard guard) {
        Set<BlockPos> changed = new LinkedHashSet<>();
        Refusal blocked = check(level, pos, wanted, guard, null, false);
        if (blocked != null) {
            return new Outcome(changed, blocked);
        }
        write(level, pos, wanted, changed);
        // A single block keeps the build's old contract: the cell holds the wanted block. Vanilla recomputes
        // connections and shapes (a fence, a pane, a stair), so the exact state is not ours to demand.
        Refusal rejected = verifyKind(level, pos, wanted);
        return new Outcome(changed, rejected);
    }

    /** The upper half is always directly above the lower one; only its state can be wrong. */
    private static Outcome reconcileDoor(ServerLevel level, BlockPos lower, BlockState lowerWanted, CellGuard guard) {
        BlockPos upper = lower.above();
        BlockState upperWanted = lowerWanted.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        Set<BlockPos> changed = new LinkedHashSet<>();
        Refusal blocked = check(level, lower, lowerWanted, guard, lower, true);
        if (blocked == null) {
            blocked = check(level, upper, upperWanted, guard, lower, false);
        }
        if (blocked != null) {
            return new Outcome(changed, blocked);
        }
        write(level, upper, upperWanted, changed);
        write(level, lower, lowerWanted, changed);
        Refusal rejected = verifyPair(level, lower, lowerWanted);
        if (rejected == null) {
            rejected = verifyPair(level, upper, upperWanted);
        }
        return new Outcome(changed, rejected);
    }

    private static Outcome reconcileBed(ServerLevel level, BlockPos foot, BlockState footWanted, CellGuard guard) {
        Direction facing = footWanted.getValue(HorizontalDirectionalBlock.FACING);
        BlockPos head = foot.relative(facing);
        BlockState headWanted = footWanted.setValue(AbstractBedBlock.PART, BedPart.HEAD);
        BlockPos stray = strayHead(level, foot, headWanted.getBlock(), head);
        Set<BlockPos> changed = new LinkedHashSet<>();
        Refusal blocked = check(level, foot, footWanted, guard, foot, true);
        if (blocked == null) {
            blocked = check(level, head, headWanted, guard, foot, false);
        }
        if (blocked == null && stray != null) {
            blocked = check(level, stray, Blocks.AIR.defaultBlockState(), guard, foot, false);
        }
        if (blocked != null) {
            return new Outcome(changed, blocked);
        }
        write(level, head, headWanted, changed);
        write(level, foot, footWanted, changed);
        if (stray != null) {
            write(level, stray, Blocks.AIR.defaultBlockState(), changed);
        }
        Refusal rejected = verifyPair(level, foot, footWanted);
        if (rejected == null) {
            rejected = verifyPair(level, head, headWanted);
        }
        if (rejected == null && stray != null) {
            rejected = verifyAir(level, stray);
        }
        return new Outcome(changed, rejected);
    }

    /**
     * The other half where the placement left it: a bed half of the same block whose own facing points back at the
     * foot. A same-colour bed beside this one points elsewhere and is not this bed's partner.
     */
    private static BlockPos strayHead(ServerLevel level, BlockPos foot, Block headBlock, BlockPos wantedHead) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = foot.relative(direction);
            if (candidate.equals(wantedHead)) {
                continue;
            }
            BlockState state = level.getBlockState(candidate);
            if (isHalfOf(state, headBlock, BedPart.HEAD, candidate, foot)) {
                return candidate;
            }
        }
        return null;
    }

    /** True when the state at {@code at} is this pair's half of {@code part} and its facing points at {@code foot}. */
    private static boolean isHalfOf(BlockState state, Block block, BedPart part, BlockPos at, BlockPos foot) {
        if (state.getBlock() != block
                || !state.hasProperty(AbstractBedBlock.PART)
                || state.getValue(AbstractBedBlock.PART) != part
                || !state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return false;
        }
        return partnerOf(at, state).equals(foot);
    }

    /**
     * Every precondition for writing {@code expected} at {@code pos}: the chunk is loaded, the guard permits it, and
     * the cell is empty, replaceable, already the wanted state, or this pair's own half in another state. A same-block
     * half counts as this pair's only at its source, or where its own facing points back at the source — another bed
     * of the same colour is somebody else's.
     */
    private static Refusal check(
            ServerLevel level,
            BlockPos pos,
            BlockState expected,
            CellGuard guard,
            BlockPos pairSource,
            boolean source) {
        if (!level.hasChunkAt(pos)) {
            return new Refusal(pos, new PlacementRefusal.Unloaded(toPos(pos), 0));
        }
        PlacementRefusal guarded = guard.refusalAt(pos).orElse(null);
        if (guarded != null) {
            return new Refusal(pos, guarded);
        }
        BlockState current = level.getBlockState(pos);
        if (expected.isAir() || current.equals(expected) || current.isAir() || current.canBeReplaced()) {
            return null;
        }
        if (current.getBlock() == expected.getBlock()) {
            // The same block in another state: a stair shape, a slab type, a pane connection, or this pair's own half.
            // For a bed or door it counts only when it is this pair's half, so another bed is never repainted.
            if (!isPair(expected) || source || belongsTo(current, pos, pairSource)) {
                return null;
            }
        }
        return new Refusal(pos, new PlacementRefusal.ForeignBlock(toPos(pos), blockId(current), 0));
    }

    /** True when the half at {@code at} is the other half of the pair rooted at {@code pairSource}. */
    private static boolean belongsTo(BlockState state, BlockPos at, BlockPos pairSource) {
        if (pairSource == null) {
            return false;
        }
        return partnerOf(at, state).equals(pairSource);
    }

    private static void write(ServerLevel level, BlockPos pos, BlockState wanted, Set<BlockPos> changed) {
        if (!level.getBlockState(pos).equals(wanted)) {
            level.setBlock(pos, wanted, Block.UPDATE_CLIENTS);
            changed.add(pos);
        }
    }

    /** A pair's half must match the blueprint's structure, ignoring the properties a player's interaction changes. */
    private static Refusal verifyPair(ServerLevel level, BlockPos pos, BlockState expected) {
        BlockState actual = level.getBlockState(pos);
        if (structurallyEqual(actual, expected)) {
            return null;
        }
        return new Refusal(pos, new PlacementRefusal.Rejected(toPos(pos), describe(expected), describe(actual), 0));
    }

    /** A single block only has to be the wanted block, as the build has always required. */
    private static Refusal verifyKind(ServerLevel level, BlockPos pos, BlockState expected) {
        BlockState actual = level.getBlockState(pos);
        if (actual.getBlock() == expected.getBlock()) {
            return null;
        }
        return new Refusal(pos, new PlacementRefusal.Rejected(toPos(pos), describe(expected), describe(actual), 0));
    }

    private static Refusal verifyAir(ServerLevel level, BlockPos pos) {
        BlockState actual = level.getBlockState(pos);
        if (actual.isAir()) {
            return null;
        }
        return new Refusal(pos, new PlacementRefusal.Rejected(toPos(pos), "air", describe(actual), 0));
    }

    /** The cell that carries the pair's state: a door's lower half or a bed's foot. */
    private static BlockPos sourceOf(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof DoorBlock) {
            return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        }
        return state.getValue(AbstractBedBlock.PART) == BedPart.FOOT ? pos : footOf(pos, state);
    }

    private static BlockState sourceWanted(BlockState wanted) {
        if (wanted.getBlock() instanceof DoorBlock) {
            return wanted.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        }
        return wanted.setValue(AbstractBedBlock.PART, BedPart.FOOT);
    }

    private static BlockPos footOf(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        return pos.relative(facing.getOpposite());
    }

    private static BlockPos partnerOf(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof DoorBlock) {
            return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        }
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        return state.getValue(AbstractBedBlock.PART) == BedPart.FOOT
                ? pos.relative(facing)
                : pos.relative(facing.getOpposite());
    }

    private static BlockState partnerWanted(BlockState wanted) {
        if (wanted.getBlock() instanceof DoorBlock) {
            DoubleBlockHalf half = wanted.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                    ? DoubleBlockHalf.UPPER
                    : DoubleBlockHalf.LOWER;
            return wanted.setValue(DoorBlock.HALF, half);
        }
        BedPart part = wanted.getValue(AbstractBedBlock.PART) == BedPart.FOOT ? BedPart.HEAD : BedPart.FOOT;
        return wanted.setValue(AbstractBedBlock.PART, part);
    }

    /** A chunk read that never generates terrain: the preflight may only report what it can see. */
    private static boolean readable(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String describe(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    private static Pos toPos(BlockPos pos) {
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }
}
