package dev.friendai.task.build;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.interact.CompanionHands;
import java.util.ArrayList;
import java.util.List;
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
 */
public final class Site {
    public static final int MAX_FOUNDATION = 4;
    private static final int MAX_LISTED_PROBLEMS = 5;

    /**
     * @param toClear    natural blocks to break before building
     * @param foundation fill blocks to place below the structure
     * @param problems   why the site cannot be used, most important first
     */
    public record SiteReport(List<BlockPos> toClear, List<Placement.PlacedSpec> foundation, List<String> problems) {
        public SiteReport {
            toClear = List.copyOf(toClear);
            foundation = List.copyOf(foundation);
            problems = List.copyOf(problems);
        }
    }

    private Site() {}

    public static SiteReport check(
            ServerLevel level, Placement.PlacedBlueprint placed, CompanionEntity companion, boolean clearSite) {
        CompanionHands hands = companion.hands();
        List<BlockPos> toClear = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        for (Placement.PlacedSpec spec : placed.specs()) {
            BlockPos pos = spec.pos();
            BlockState current = level.getBlockState(pos);
            if (current.equals(spec.wanted())) {
                continue;
            }
            if (!hands.canInteract(pos)) {
                problems.add(coords(pos) + " is protected");
                continue;
            }
            if (isNatural(current)) {
                if (clearSite) {
                    toClear.add(pos);
                } else {
                    problems.add(siteProblem(current, pos));
                }
                continue;
            }
            if (current.isAir()
                    || current.canBeReplaced()
                    || !current.getFluidState().isEmpty()) {
                continue;
            }
            problems.add(siteProblem(current, pos));
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
                problems.add(
                        "the ground under " + coords(spec.pos()) + " drops more than " + MAX_FOUNDATION + " blocks");
            }
        }

        return new SiteReport(toClear, foundation, capped(problems));
    }

    /** Dirt when the companion carries more of it than cobblestone, otherwise cobblestone. */
    private static BlockState foundationMaterial(CompanionEntity companion) {
        int dirtCount = count(companion, Items.DIRT);
        int cobbleCount = count(companion, Items.COBBLESTONE);
        return dirtCount > cobbleCount ? Blocks.DIRT.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
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

    private static String siteProblem(BlockState state, BlockPos pos) {
        return "the site isn't clear: " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " at " + coords(pos);
    }

    private static List<String> capped(List<String> problems) {
        if (problems.size() <= MAX_LISTED_PROBLEMS) {
            return problems;
        }
        List<String> listed = new ArrayList<>(problems.subList(0, MAX_LISTED_PROBLEMS));
        listed.add("and " + (problems.size() - MAX_LISTED_PROBLEMS) + " more");
        return listed;
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
