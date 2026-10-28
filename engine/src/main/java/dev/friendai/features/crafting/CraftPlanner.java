package dev.friendai.features.crafting;

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
 * needed, leftovers feed later steps, and alternative recipes compete on total crafts. A recipe whose
 * station is neither nearby nor carried makes its station item a craft goal of its own.
 */
public final class CraftPlanner {
    /** How many nested crafts the search may chain. */
    public static final int MAX_DEPTH = 5;

    private CraftPlanner() {}

    public static CraftPlan plan(
            String target, int count, Map<String, Integer> inventory, RecipeBook book, Set<Station> stationsNearby) {
        Objects.requireNonNull(target, "target");
        if (target.isEmpty()) {
            throw new IllegalArgumentException("target must not be empty");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive: " + count);
        }
        Objects.requireNonNull(book, "book");
        Sim sim = new Sim();
        if (inventory != null) {
            inventory.forEach((item, amount) -> {
                if (item != null && amount != null && amount > 0) {
                    sim.items.put(item, amount);
                }
            });
        }
        Set<Station> nearby = stationsNearby == null ? EnumSet.noneOf(Station.class) : stationsNearby;
        Attempt attempt = new Search(target, book, nearby).need(target, count, 0, new LinkedHashSet<>(), sim);
        List<CraftStep> steps = orderedSteps(attempt.steps(), book);
        Set<Station> stationsUsed = EnumSet.noneOf(Station.class);
        for (CraftStep step : steps) {
            if (step.recipe().station() != Station.INVENTORY) {
                stationsUsed.add(step.recipe().station());
            }
        }
        return new CraftPlan(steps, attempt.missing, stationsUsed, attempt.stationsCrafted);
    }

    /**
     * Plans {@code count} new items from the same inventory, even when it already carries some of the target: a
     * craft was asked for, so a carried stack must not turn the plan into a no-op (the companion always carries
     * the crafting table it uses as a station, and a request to craft one has to produce a new one). Carried
     * ingredients, intermediates included, are used as usual.
     */
    public static CraftPlan planNew(
            String target, int count, Map<String, Integer> inventory, RecipeBook book, Set<Station> stationsNearby) {
        Objects.requireNonNull(target, "target");
        Map<String, Integer> others = new LinkedHashMap<>();
        if (inventory != null) {
            inventory.forEach((item, amount) -> {
                if (item != null && !item.equals(target) && amount != null && amount > 0) {
                    others.put(item, amount);
                }
            });
        }
        return plan(target, count, others, book, stationsNearby);
    }

    /**
     * Puts the merged steps in dependency order: a step that produces an item another step consumes runs first,
     * and a station step follows the step that crafts its station item. Steps merge by recipe id, so a recipe
     * first used early keeps its position while a later use may add dependencies after it; without this pass the
     * executor would run such a step before the ingredients that use is counting on. Ties keep insertion order.
     * A cycle (not expected for a plan the search accepted) falls back to insertion order.
     */
    private static List<CraftStep> orderedSteps(List<CraftStep> steps, RecipeBook book) {
        int n = steps.size();
        if (n < 2) {
            return List.copyOf(steps);
        }
        List<List<Integer>> before = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            before.add(new ArrayList<>());
        }
        int[] waiting = new int[n];
        for (int producer = 0; producer < n; producer++) {
            String output = steps.get(producer).recipe().output();
            for (int consumer = 0; consumer < n; consumer++) {
                if (consumer != producer && uses(steps.get(consumer), output)) {
                    before.get(producer).add(consumer);
                    waiting[consumer]++;
                }
            }
        }
        for (int consumer = 0; consumer < n; consumer++) {
            Station station = steps.get(consumer).recipe().station();
            if (station == Station.INVENTORY) {
                continue;
            }
            String stationItem = book.stationItem(station);
            if (stationItem == null) {
                continue;
            }
            for (int producer = 0; producer < n; producer++) {
                if (producer != consumer
                        && steps.get(producer).recipe().output().equals(stationItem)) {
                    before.get(producer).add(consumer);
                    waiting[consumer]++;
                }
            }
        }
        List<CraftStep> ordered = new ArrayList<>(n);
        boolean[] emitted = new boolean[n];
        while (ordered.size() < n) {
            int next = -1;
            for (int i = 0; i < n; i++) {
                if (!emitted[i] && waiting[i] == 0) {
                    next = i;
                    break;
                }
            }
            if (next < 0) {
                return List.copyOf(steps);
            }
            emitted[next] = true;
            ordered.add(steps.get(next));
            for (int consumer : before.get(next)) {
                waiting[consumer]--;
            }
        }
        return List.copyOf(ordered);
    }

    /** True when a step consumes the item in any ingredient slot. */
    private static boolean uses(CraftStep step, String item) {
        for (Ingredient ingredient : step.recipe().ingredients()) {
            if (ingredient.items().contains(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The simulated world of one search branch: the items, the station items that exist but must never be
     * consumed, and the stations already crafted so their item is crafted at most once.
     */
    private static final class Sim {
        final Map<String, Integer> items = new LinkedHashMap<>();
        final Set<String> reserved = new LinkedHashSet<>();
        final Set<Station> craftedStations = EnumSet.noneOf(Station.class);

        Sim copy() {
            Sim copy = new Sim();
            copy.items.putAll(items);
            copy.reserved.addAll(reserved);
            copy.craftedStations.addAll(craftedStations);
            return copy;
        }

        void adopt(Sim other) {
            items.clear();
            items.putAll(other.items);
            reserved.clear();
            reserved.addAll(other.reserved);
            craftedStations.clear();
            craftedStations.addAll(other.craftedStations);
        }
    }

    private static final class Search {
        private final String target;
        private final RecipeBook book;
        private final Set<Station> stationsNearby;
        private final Map<String, Boolean> cycles = new HashMap<>();

        Search(String target, RecipeBook book, Set<Station> stationsNearby) {
            this.target = target;
            this.book = book;
            this.stationsNearby = stationsNearby;
        }

        /**
         * Obtains {@code qty} of {@code item} from {@code sim}, crafting it if needed. The carried items are
         * consumed from {@code sim}; a successful attempt leaves leftover outputs in it.
         */
        Attempt need(String item, int qty, int depth, Set<String> path, Sim sim) {
            if (!sim.reserved.contains(item)) {
                int carried = sim.items.getOrDefault(item, 0);
                int taken = Math.min(carried, qty);
                if (taken > 0) {
                    sim.items.put(item, carried - taken);
                    qty -= taken;
                }
            }
            if (qty == 0) {
                return new Attempt();
            }
            List<Recipe> recipes = craftable(item);
            if (depth >= MAX_DEPTH || path.contains(item) || recipes.isEmpty()) {
                return Attempt.missing(item, qty);
            }
            Attempt best = null;
            Sim bestSim = null;
            List<Attempt> failed = new ArrayList<>();
            for (Recipe recipe : recipes) {
                Sim copy = sim.copy();
                Attempt attempt = tryRecipe(item, recipe, qty, depth, path, copy);
                if (attempt.ok()) {
                    if (best == null || cheaper(attempt, best)) {
                        best = attempt;
                        bestSim = copy;
                    }
                } else {
                    failed.add(attempt);
                }
            }
            if (best != null) {
                sim.adopt(bestSim);
                return best;
            }
            if (!anyIngredientPresent(recipes, sim) && (!item.equals(target) || cyclic(item))) {
                // Nothing can start: a dependency is reported itself (a station goal, an iron ingot). The
                // requested target is the exception, because naming its ingredients is more useful there —
                // unless it only feeds its own recipe loop (ingot → block → ingot).
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

        /**
         * The recipes the planner may execute. Cooking recipes share the book with crafting ones but are the
         * cooking skills' business: an item that only cooks is reported missing as itself.
         */
        private List<Recipe> craftable(String item) {
            List<Recipe> recipes = book.producing(item);
            boolean cooking = false;
            for (Recipe recipe : recipes) {
                if (recipe.station().isCooking()) {
                    cooking = true;
                    break;
                }
            }
            if (!cooking) {
                return recipes;
            }
            List<Recipe> filtered = new ArrayList<>(recipes.size());
            for (Recipe recipe : recipes) {
                if (!recipe.station().isCooking()) {
                    filtered.add(recipe);
                }
            }
            return filtered;
        }

        /** Fewer total crafts, then a station that is already available, then book order (the caller keeps it). */
        private static boolean cheaper(Attempt candidate, Attempt best) {
            if (candidate.crafts() != best.crafts()) {
                return candidate.crafts() < best.crafts();
            }
            return candidate.stationPenalty < best.stationPenalty;
        }

        private Attempt tryRecipe(String item, Recipe recipe, int qty, int depth, Set<String> path, Sim sim) {
            int times = (qty + recipe.outputCount() - 1) / recipe.outputCount();
            Set<String> nextPath = new LinkedHashSet<>(path);
            nextPath.add(item);
            Attempt attempt = new Attempt();
            Attempt station = ensureStation(recipe.station(), depth + 1, nextPath, sim);
            attempt.merge(station);
            attempt.stationPenalty = station.stationPenalty;
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
                sim.items.merge(recipe.output(), leftover, Integer::sum);
            }
            attempt.addStep(recipe, times);
            return attempt;
        }

        /**
         * Makes {@code station} available: nearby and carried stations cost nothing, a missing one becomes a
         * craft goal of its own whose item then stays in the simulation reserved for the station.
         */
        private Attempt ensureStation(Station station, int depth, Set<String> path, Sim sim) {
            if (station == Station.INVENTORY || stationsNearby.contains(station)) {
                return new Attempt();
            }
            String item = book.stationItem(station);
            if (item == null) {
                // A book that names no item for a non-inventory station leaves nothing to craft or carry.
                return Attempt.missing(station.noun(), 1);
            }
            if (sim.craftedStations.contains(station) || sim.reserved.contains(item)) {
                return new Attempt();
            }
            if (sim.items.getOrDefault(item, 0) > 0) {
                sim.reserved.add(item);
                return new Attempt();
            }
            Attempt attempt = need(item, 1, depth, path, sim);
            if (!attempt.ok()) {
                return attempt;
            }
            if (sim.items.getOrDefault(item, 0) < 1) {
                sim.items.put(item, 1);
            }
            sim.reserved.add(item);
            sim.craftedStations.add(station);
            attempt.stationsCrafted.add(station);
            attempt.stationPenalty = 1;
            return attempt;
        }

        private Attempt fill(Ingredient ingredient, int depth, Set<String> path, Sim sim) {
            for (String id : ingredient.items()) {
                if (sim.reserved.contains(id)) {
                    continue;
                }
                int carried = sim.items.getOrDefault(id, 0);
                if (carried > 0) {
                    sim.items.put(id, carried - 1);
                    return new Attempt();
                }
            }
            for (String id : ingredient.items()) {
                Sim copy = sim.copy();
                Attempt attempt = need(id, 1, depth, path, copy);
                if (attempt.ok()) {
                    sim.adopt(copy);
                    return attempt;
                }
            }
            return Attempt.missing(ingredient.label(), 1);
        }

        private static boolean anyIngredientPresent(List<Recipe> recipes, Sim sim) {
            for (Recipe recipe : recipes) {
                for (Ingredient ingredient : recipe.ingredients()) {
                    for (String id : ingredient.items()) {
                        if (!sim.reserved.contains(id) && sim.items.getOrDefault(id, 0) > 0) {
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
            for (Recipe recipe : craftable(item)) {
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

    /**
     * Steps merged by recipe id (dependencies first), the missing labels, the total craft count, the stations
     * whose item this attempt crafted, and whether the attempt's own recipe needed a crafted station.
     */
    private static final class Attempt {
        private final Map<String, Integer> stepTimes = new LinkedHashMap<>();
        private final Map<String, Recipe> stepRecipes = new HashMap<>();
        private final Map<String, Integer> missing = new LinkedHashMap<>();
        private final Set<Station> stationsCrafted = EnumSet.noneOf(Station.class);
        private int crafts;
        private int stationPenalty;

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
            stationsCrafted.addAll(other.stationsCrafted);
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
