package dev.friedai.crafting;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Plans the crafts that make an item from a simulated inventory: intermediate items are crafted as
 * needed, leftovers feed later steps, and alternative recipes compete on total crafts.
 */
public final class CraftPlanner {
    /** How many nested crafts the search may chain. */
    public static final int MAX_DEPTH = 5;

    private CraftPlanner() {}

    public static CraftPlan plan(String target, int count, Map<String, Integer> inventory, RecipeBook book,
            Set<Station> stationsNearby) {
        Objects.requireNonNull(target, "target");
        if (target.isEmpty()) {
            throw new IllegalArgumentException("target must not be empty");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive: " + count);
        }
        Objects.requireNonNull(book, "book");
        Map<String, Integer> simulated = new LinkedHashMap<>();
        if (inventory != null) {
            inventory.forEach((item, amount) -> {
                if (item != null && amount != null && amount > 0) {
                    simulated.put(item, amount);
                }
            });
        }
        Attempt attempt = new Search(book).need(target, count, 0, new LinkedHashSet<>(), simulated);
        List<CraftStep> steps = attempt.steps();
        Set<Station> stationsUsed = EnumSet.noneOf(Station.class);
        for (CraftStep step : steps) {
            if (step.recipe().station() != Station.INVENTORY) {
                stationsUsed.add(step.recipe().station());
            }
        }
        return new CraftPlan(steps, attempt.missing, stationsUsed, EnumSet.noneOf(Station.class));
    }

    private static final class Search {
        private final RecipeBook book;
        private final Map<String, Boolean> cycles = new HashMap<>();

        Search(RecipeBook book) {
            this.book = book;
        }

        /**
         * Obtains {@code qty} of {@code item} from {@code sim}, crafting it if needed. The carried items are
         * consumed from {@code sim}; a successful attempt leaves leftover outputs in it.
         */
        Attempt need(String item, int qty, int depth, Set<String> path, Map<String, Integer> sim) {
            int carried = sim.getOrDefault(item, 0);
            int taken = Math.min(carried, qty);
            if (taken > 0) {
                sim.put(item, carried - taken);
                qty -= taken;
            }
            if (qty == 0) {
                return new Attempt();
            }
            List<Recipe> recipes = book.producing(item);
            if (depth >= MAX_DEPTH || path.contains(item) || recipes.isEmpty()) {
                return Attempt.missing(item, qty);
            }
            Attempt best = null;
            Map<String, Integer> bestSim = null;
            List<Attempt> failed = new ArrayList<>();
            for (Recipe recipe : recipes) {
                Map<String, Integer> copy = new LinkedHashMap<>(sim);
                Attempt attempt = tryRecipe(item, recipe, qty, depth, path, copy);
                if (attempt.ok()) {
                    if (best == null || attempt.crafts() < best.crafts()) {
                        best = attempt;
                        bestSim = copy;
                    }
                } else {
                    failed.add(attempt);
                }
            }
            if (best != null) {
                sim.clear();
                sim.putAll(bestSim);
                return best;
            }
            if (!anyIngredientPresent(recipes, sim) && cyclic(item)) {
                // Nothing can start and every recipe only loops back to the item: report the item, not its
                // siblings (2 × iron_ingot rather than 1 × iron_block).
                return Attempt.missing(item, qty);
            }
            Attempt chosen = failed.get(0);
            for (int i = 1; i < failed.size(); i++) {
                if (failed.get(i).missingTotal() < chosen.missingTotal()) {
                    chosen = failed.get(i);
                }
            }
            return chosen;
        }

        private Attempt tryRecipe(String item, Recipe recipe, int qty, int depth, Set<String> path,
                Map<String, Integer> sim) {
            int times = (qty + recipe.outputCount() - 1) / recipe.outputCount();
            Set<String> nextPath = new LinkedHashSet<>(path);
            nextPath.add(item);
            Attempt attempt = new Attempt();
            for (int craft = 0; craft < times; craft++) {
                for (Ingredient ingredient : recipe.ingredients()) {
                    Attempt filled = fill(ingredient, depth + 1, nextPath, sim);
                    attempt.merge(filled);
                }
            }
            if (!attempt.ok()) {
                return attempt;
            }
            int leftover = times * recipe.outputCount() - qty;
            if (leftover > 0) {
                sim.merge(recipe.output(), leftover, Integer::sum);
            }
            attempt.addStep(recipe, times);
            return attempt;
        }

        private Attempt fill(Ingredient ingredient, int depth, Set<String> path, Map<String, Integer> sim) {
            for (String id : ingredient.items()) {
                int carried = sim.getOrDefault(id, 0);
                if (carried > 0) {
                    sim.put(id, carried - 1);
                    return new Attempt();
                }
            }
            for (String id : ingredient.items()) {
                Map<String, Integer> copy = new LinkedHashMap<>(sim);
                Attempt attempt = need(id, 1, depth, path, copy);
                if (attempt.ok()) {
                    sim.clear();
                    sim.putAll(copy);
                    return attempt;
                }
            }
            return Attempt.missing(ingredient.label(), 1);
        }

        private static boolean anyIngredientPresent(List<Recipe> recipes, Map<String, Integer> sim) {
            for (Recipe recipe : recipes) {
                for (Ingredient ingredient : recipe.ingredients()) {
                    for (String id : ingredient.items()) {
                        if (sim.getOrDefault(id, 0) > 0) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * True when some recipe path leads from {@code item} back to itself, like ingot → block → ingot.
         * Such an item is reported missing itself instead of naming a sibling that can only be made from it.
         */
        private boolean cyclic(String item) {
            return cycles.computeIfAbsent(item, key -> reaches(key, key, new LinkedHashSet<>()));
        }

        private boolean reaches(String item, String target, Set<String> seen) {
            for (Recipe recipe : book.producing(item)) {
                for (Ingredient ingredient : recipe.ingredients()) {
                    for (String id : ingredient.items()) {
                        if (id.equals(target)) {
                            return true;
                        }
                        if (seen.add(id) && reaches(id, target, seen)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    /** Steps merged by recipe id (dependencies first), the missing labels, and the total craft count. */
    private static final class Attempt {
        private final Map<String, Integer> stepTimes = new LinkedHashMap<>();
        private final Map<String, Recipe> stepRecipes = new HashMap<>();
        private final Map<String, Integer> missing = new LinkedHashMap<>();
        private int crafts;

        static Attempt missing(String label, int qty) {
            Attempt attempt = new Attempt();
            attempt.missing.put(label, qty);
            return attempt;
        }

        boolean ok() {
            return missing.isEmpty();
        }

        int crafts() {
            return crafts;
        }

        int missingTotal() {
            return missing.values().stream().mapToInt(Integer::intValue).sum();
        }

        void merge(Attempt other) {
            other.stepTimes.forEach((id, times) -> {
                stepTimes.merge(id, times, Integer::sum);
                stepRecipes.putIfAbsent(id, other.stepRecipes.get(id));
            });
            other.missing.forEach((label, qty) -> missing.merge(label, qty, Integer::sum));
            crafts += other.crafts;
        }

        void addStep(Recipe recipe, int times) {
            stepTimes.merge(recipe.id(), times, Integer::sum);
            stepRecipes.putIfAbsent(recipe.id(), recipe);
            crafts += times;
        }

        List<CraftStep> steps() {
            List<CraftStep> steps = new ArrayList<>(stepTimes.size());
            stepTimes.forEach((id, times) -> steps.add(new CraftStep(stepRecipes.get(id), times)));
            return steps;
        }
    }
}
