package dev.friendai.features.acquisition;

import dev.friendai.features.crafting.Recipe;
import dev.friendai.features.crafting.RecipeBook;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Walks the recipes and the non-recipe sources of an item to the blocks that can supply it. The walk is
 * breadth-first, so a shallow source is found before a deep one, and it is bounded: a branch cut at the depth limit or
 * by the work budget is reported as {@link Coverage#PARTIAL} with the blocks found so far, so a caller can say "there
 * may be more" instead of treating an unconsidered source as a missing one.
 */
public final class RecipeChain {
    /** How far the walk went: everything explored, some branches cut, or nothing known at all. */
    public enum Coverage {
        COMPLETE,
        PARTIAL,
        UNSUPPORTED
    }

    /** The bounds of one walk. The defaults are what a resource lookup uses. */
    public record Policy(int maxDepth, int maxItems) {
        public static final Policy DEFAULT = new Policy(5, 512);

        public Policy {
            if (maxDepth < 1) throw new IllegalArgumentException("maxDepth must be positive: " + maxDepth);
            if (maxItems < 1) throw new IllegalArgumentException("maxItems must be positive: " + maxItems);
        }
    }

    /**
     * The blocks that can supply the item and how thoroughly they were found. {@code limit} names the bound that cut
     * the walk, and is empty when nothing was cut.
     */
    public record Result(Set<String> blockIds, Coverage coverage, boolean smelting, String limit) {
        public Result {
            blockIds = Set.copyOf(blockIds == null ? Set.of() : blockIds);
            Objects.requireNonNull(coverage, "coverage");
            limit = limit == null ? "" : limit;
        }

        public boolean executable() {
            return !blockIds.isEmpty();
        }
    }

    private RecipeChain() {}

    public static Result resolve(String itemId, RecipeBook recipes, SourceBook sources) {
        return resolve(itemId, recipes, sources, Policy.DEFAULT);
    }

    public static Result resolve(String itemId, RecipeBook recipes, SourceBook sources, Policy policy) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(policy, "policy");
        Set<String> blocks = new LinkedHashSet<>();
        Set<String> scheduled = new LinkedHashSet<>();
        ArrayDeque<ItemDepth> pending = new ArrayDeque<>();
        pending.add(new ItemDepth(itemId, 0));
        scheduled.add(itemId);
        boolean smelting = false;
        String limit = "";
        int expanded = 0;
        while (!pending.isEmpty()) {
            ItemDepth current = pending.removeFirst();
            if (expanded >= policy.maxItems()) {
                limit = budgetLimit(policy);
                break;
            }
            expanded++;
            List<Recipe> producing = recipes.producing(current.id());
            boolean crafted = crafted(producing);
            for (Source source : sources.sourcesOf(current.id())) {
                if (source instanceof Source.Mine mine) {
                    // A placed block of a crafted item is not the source of the item: follow the recipe instead.
                    if (!crafted || !mine.block().equals(current.id())) blocks.add(mine.block());
                }
                if (source instanceof Source.Harvest harvest) blocks.add(harvest.crop());
            }
            for (Recipe recipe : producing) {
                if (recipe.station().isCooking()) smelting = true;
            }
            if (current.depth() >= policy.maxDepth()) {
                // Only a recipe ingredient this deep that nothing else reaches was actually lost to the limit.
                boolean lost = producing.stream()
                        .flatMap(recipe -> recipe.ingredients().stream())
                        .flatMap(ingredient -> ingredient.items().stream())
                        .anyMatch(id -> !scheduled.contains(id));
                if (lost) limit = depthLimit(policy);
                continue;
            }
            for (Recipe recipe : producing) {
                for (var ingredient : recipe.ingredients()) {
                    for (String id : ingredient.items()) {
                        if (scheduled.add(id)) pending.addLast(new ItemDepth(id, current.depth() + 1));
                    }
                }
            }
        }
        Coverage coverage;
        if (!limit.isEmpty()) {
            coverage = Coverage.PARTIAL;
        } else if (blocks.isEmpty()) {
            coverage = Coverage.UNSUPPORTED;
        } else {
            coverage = Coverage.COMPLETE;
        }
        return new Result(blocks, coverage, smelting, limit);
    }

    private static boolean crafted(List<Recipe> producing) {
        return producing.stream().anyMatch(recipe -> !recipe.station().isCooking());
    }

    private static String depthLimit(Policy policy) {
        return "the recipe depth limit of " + (policy.maxDepth() + 1) + " levels";
    }

    private static String budgetLimit(Policy policy) {
        return "the recipe work budget of " + policy.maxItems() + " items";
    }

    private record ItemDepth(String id, int depth) {}
}
