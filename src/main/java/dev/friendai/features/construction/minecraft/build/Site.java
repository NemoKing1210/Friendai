package dev.friendai.features.construction.minecraft.build;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.construction.blueprint.PlacementRefusal;
import dev.friendai.features.placements.PlacedBlock;
import dev.friendai.features.placements.PlacedQuery;
import dev.friendai.features.placements.minecraft.PlacedService;
import dev.friendai.foundation.spatial.Pos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Checks the ground under a planned structure: what must be cleared, how deep the foundation goes, and what stops the
 * build. Never plans to break a block a player put there.
 *
 * <p>Every stop is a typed {@link PlacementRefusal}, not prose: a foreign block is foreign, a block this build placed
 * itself is not an obstruction, and protection says whether it is the server or a named place. The caller supplies
 * the ownership predicate, because the build is the one that knows what it placed.
 */
public final class Site {
    public static final int MAX_FOUNDATION = 4;
    private static final int MAX_LISTED_PROBLEMS = 5;
    /**
     * @param toClear    natural blocks to break before building
     * @param foundation fill blocks to place below the structure
     * @param refusals   why the site cannot be used, most important first
     */
    public record SiteReport(
            List<BlockPos> toClear, List<Placement.PlacedSpec> foundation, List<PlacementRefusal> refusals) {
        public SiteReport {
            toClear = List.copyOf(toClear);
            foundation = List.copyOf(foundation);
            refusals = List.copyOf(refusals);
        }
        /** The refusals as one line each, for the preview text and older callers. */
        public List<String> problems() {
            return refusals.stream().map(PlacementRefusal::describe).toList();
        }
    }

    private Site() {}

    public static SiteReport check(
            ServerLevel level, Placement.PlacedBlueprint placed, CompanionEntity companion, boolean clearSite) {
        return check(level, placed, companion, clearSite, pos -> false);
    }

    /**
     * @param owned cells the companion placed itself, from this build or an earlier one; they are never reported as
     *     "the site isn't clear" because the build knows how to take its own block back
     */
    public static SiteReport check(
            ServerLevel level,
            Placement.PlacedBlueprint placed,
            CompanionEntity companion,
            boolean clearSite,
            Predicate<BlockPos> owned) {
        Staged staged = new Staged(level, placed, companion, clearSite, owned);
        staged.step(Integer.MAX_VALUE);
        return staged.report();
    }

    /**
     * The same check, in slices: every {@link #step} reads at most {@code budget} cells, so a near-limit footprint
     * cannot stall one server tick. One pass produces the report and the specs still missing, because both ask the
     * same question of the same cell.
     *
     * <p>A cell in a chunk that is not loaded is never read: the preflight must not load chunks, so the cell is
     * counted as remaining (the conservative answer for material) and the placement path revalidates it before it
     * mutates anything.
     */
    public static final class Staged {
        private final ServerLevel level;
        private final Placement.PlacedBlueprint placed;
        private final CompanionEntity companion;
        private final boolean clearSite;
        private final Predicate<BlockPos> owned;
        private final BlockState fill;
        private final List<BlockPos> toClear = new ArrayList<>();
        private final List<Placement.PlacedSpec> foundation = new ArrayList<>();
        /** Site problems in blueprint order, then the missing supports, so the report keeps its old order. */
        private final List<PlacementRefusal> obstructions = new ArrayList<>();

        private final List<PlacementRefusal> supports = new ArrayList<>();
        private final List<Placement.PlacedSpec> remaining = new ArrayList<>();
        private int cursor;

        public Staged(
                ServerLevel level,
                Placement.PlacedBlueprint placed,
                CompanionEntity companion,
                boolean clearSite,
                Predicate<BlockPos> owned) {
            this.level = level;
            this.placed = placed;
            this.companion = companion;
            this.clearSite = clearSite;
            this.owned = owned;
            this.fill = foundationMaterial(companion);
        }

        public boolean done() {
            return cursor >= placed.specs().size();
        }

        /** Cells read so far, for the progress line. */
        public int scanned() {
            return Math.min(cursor, placed.specs().size());
        }

        public int total() {
            return placed.specs().size();
        }

        /** Reads the next {@code budget} cells; a budget of zero still makes progress, so a pass can never stall. */
        public void step(int budget) {
            int end = Math.min(placed.specs().size(), cursor + Math.max(1, budget));
            for (; cursor < end; cursor++) {
                scan(placed.specs().get(cursor));
            }
        }

        /** The report of the cells scanned so far; call it after {@link #done()}. */
        public SiteReport report() {
            List<PlacementRefusal> refusals = new ArrayList<>(obstructions);
            refusals.addAll(supports);
            return new SiteReport(toClear, foundation, capped(refusals));
        }

        /** The planned specs whose world state still differs from the wanted one. */
        public List<Placement.PlacedSpec> remaining() {
            return List.copyOf(remaining);
        }

        private void scan(Placement.PlacedSpec spec) {
            BlockPos pos = spec.pos();
            if (!loaded(pos)) {
                remaining.add(spec);
                return;
            }
            BlockState current = level.getBlockState(pos);
            boolean correct = current.equals(spec.wanted());
            if (correct) {
                return;
            }
            remaining.add(spec);
            // The foundation of a base-layer cell is asked even when the cell itself is refused: an unsupported
            // structure is reported in both ways, as it always has been.
            if (!spec.wanted().isAir() && pos.getY() == placed.origin().getY()) {
                foundation(spec);
            }
            if (owned.test(pos)) {
                // An own block is handled at placement: reconciled when it is the wanted block in another state,
                // taken back otherwise. It is not foreign obstruction.
                return;
            }
            if (!companion.hands().canInteract(pos)) {
                obstructions.add(new PlacementRefusal.Protected(coords(pos), "", "", 0, 0, 0, 0));
                return;
            }
            if (isNatural(current)) {
                if (clearSite) {
                    toClear.add(pos);
                } else {
                    obstructions.add(new PlacementRefusal.ForeignBlock(coords(pos), blockId(current), 0));
                }
                return;
            }
            if (current.isAir()
                    || current.canBeReplaced()
                    || !current.getFluidState().isEmpty()) {
                return;
            }
            obstructions.add(new PlacementRefusal.ForeignBlock(coords(pos), blockId(current), 0));
        }

        private void foundation(Placement.PlacedSpec spec) {
            BlockPos below = spec.pos().below();
            int depth = 0;
            while (depth < MAX_FOUNDATION && loaded(below) && !isSolid(level.getBlockState(below))) {
                foundation.add(new Placement.PlacedSpec(below, fill));
                below = below.below();
                depth++;
            }
            if (depth == MAX_FOUNDATION && loaded(below) && !isSolid(level.getBlockState(below))) {
                supports.add(new PlacementRefusal.NoSupport(coords(spec.pos()), coords(below), false, 0));
            }
        }

        /** Whether the cell can be read without loading its chunk; a preflight reports, it does not generate world. */
        private boolean loaded(BlockPos pos) {
            return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
        }
    }

    /** Dirt when the companion carries more of it than cobblestone, otherwise cobblestone. */
    private static BlockState foundationMaterial(CompanionEntity companion) {
        int dirtCount = count(companion, Items.DIRT);
        int cobbleCount = count(companion, Items.COBBLESTONE);
        return dirtCount > cobbleCount ? Blocks.DIRT.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
    }

    /**
     * The ledger placements inside the footprint: blocks this companion put there in this or an earlier run. A build
     * adds its scaffold to this set; a preview uses only the ledger, because it has no scaffold of its own.
     */
    public static Set<BlockPos> ownedByCompanion(
            CompanionEntity companion, Placement.PlacedBlueprint placed, PlacedService placedService) {
        Set<BlockPos> owned = new HashSet<>();
        try {
            int width = 0;
            int height = 0;
            int depth = 0;
            for (Placement.PlacedSpec spec : placed.specs()) {
                width = Math.max(width, spec.pos().getX() - placed.origin().getX() + 1);
                height = Math.max(height, spec.pos().getY() - placed.origin().getY() + 1);
                depth = Math.max(depth, spec.pos().getZ() - placed.origin().getZ() + 1);
            }
            int radius = Math.max(width, Math.max(height, depth)) + 8;
            PlacedQuery.Result result = placedService.findAt(
                    companion,
                    null,
                    new Pos(
                            placed.origin().getX(),
                            placed.origin().getY(),
                            placed.origin().getZ()),
                    radius,
                    512);
            for (PlacedBlock entry : result.entries()) {
                BlockPos pos = new BlockPos(
                        entry.pos().x(), entry.pos().y(), entry.pos().z());
                if (pos.getX() >= placed.origin().getX()
                        && pos.getX() < placed.origin().getX() + Math.max(1, width)
                        && pos.getY() >= placed.origin().getY()
                        && pos.getY() < placed.origin().getY() + Math.max(1, height)
                        && pos.getZ() >= placed.origin().getZ()
                        && pos.getZ() < placed.origin().getZ() + Math.max(1, depth)) {
                    owned.add(pos);
                }
            }
        } catch (RuntimeException ignored) {
            // No ledger (or no owner) only means ownership is unknown; foreign blocks stay refusals.
        }
        return owned;
    }

    private static int count(CompanionEntity companion, Item item) {
        int total = 0;
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            var stack = companion.inventory().getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Ground the companion may cut: dirt, stone, sand, gravel, clay, leaves and logs, plus replaceable plants. */
    public static boolean isNatural(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (state.is(BlockTags.DIRT)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.SAND)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)) {
            return true;
        }
        return state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    /** A block a foundation can stand on: not air, not liquid, not replaceable. */
    private static boolean isSolid(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty() && !state.canBeReplaced();
    }

    private static List<PlacementRefusal> capped(List<PlacementRefusal> refusals) {
        if (refusals.size() <= MAX_LISTED_PROBLEMS) {
            return refusals;
        }
        List<PlacementRefusal> listed = new ArrayList<>(refusals.subList(0, MAX_LISTED_PROBLEMS));
        listed.add(new PlacementRefusal.AttemptsExhausted(
                refusals.getFirst().pos(),
                (refusals.size() - MAX_LISTED_PROBLEMS) + " more site problems",
                refusals.size()));
        return listed;
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static Pos coords(BlockPos pos) {
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }
}
