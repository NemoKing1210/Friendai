package dev.friendai.features.construction.minecraft.build;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.construction.blueprint.PlacementRefusal;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.placements.PlacedBlock;
import dev.friendai.features.placements.PlacedQuery;
import dev.friendai.features.placements.minecraft.PlacedService;
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
        List<BlockPos> toClear = new ArrayList<>();
        List<PlacementRefusal> refusals = new ArrayList<>();

        for (Placement.PlacedSpec spec : placed.specs()) {
            BlockPos pos = spec.pos();
            BlockState current = level.getBlockState(pos);
            if (current.equals(spec.wanted())) {
                continue;
            }
            if (owned.test(pos)) {
                // An own block is handled at placement: reconciled when it is the wanted block in another state,
                // taken back otherwise. It is not foreign obstruction.
                continue;
            }
            if (!companion.hands().canInteract(pos)) {
                refusals.add(new PlacementRefusal.Protected(coords(pos), "", "", 0, 0, 0, 0));
                continue;
            }
            if (isNatural(current)) {
                if (clearSite) {
                    toClear.add(pos);
                } else {
                    refusals.add(new PlacementRefusal.ForeignBlock(coords(pos), blockId(current), 0));
                }
                continue;
            }
            if (current.isAir()
                    || current.canBeReplaced()
                    || !current.getFluidState().isEmpty()) {
                continue;
            }
            refusals.add(new PlacementRefusal.ForeignBlock(coords(pos), blockId(current), 0));
        }

        List<Placement.PlacedSpec> foundation = new ArrayList<>();
        BlockState fill = foundationMaterial(companion);
        for (Placement.PlacedSpec spec : placed.specs()) {
            if (spec.wanted().isAir() || spec.pos().getY() != placed.origin().getY()) {
                continue;
            }
            if (level.getBlockState(spec.pos()).equals(spec.wanted())) {
                continue;
            }
            BlockPos below = spec.pos().below();
            int depth = 0;
            while (depth < MAX_FOUNDATION && !isSolid(level.getBlockState(below))) {
                foundation.add(new Placement.PlacedSpec(below, fill));
                below = below.below();
                depth++;
            }
            if (depth == MAX_FOUNDATION && !isSolid(level.getBlockState(below))) {
                refusals.add(new PlacementRefusal.NoSupport(coords(spec.pos()), coords(below), false, 0));
            }
        }

        return new SiteReport(toClear, foundation, capped(refusals));
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
