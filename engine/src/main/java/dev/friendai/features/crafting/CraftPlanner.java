package dev.friendai.features.crafting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

    /** Finished items one batch may ask for at once. */
    public static final int MAX_TARGETS = 8;

    /** How many of one finished item a batch may ask for. */
    public static final int MAX_TARGET_COUNT = 64;

    private CraftPlanner() {}

    /** One finished item a batch must produce. */
    public record Target(String item, int count) {
        public Target {
            if (item == null || item.isBlank()) {
                throw new IllegalArgumentException("target must not be empty");
            }
            if (count < 1 || count > MAX_TARGET_COUNT) {
                throw new IllegalArgumentException("target count must be 1.." + MAX_TARGET_COUNT + ": " + count);
            }
        }
    }

    /**
     * One raw item a target set still needs: how it is named in a message, the concrete item ids that satisfy it, and
     * how many. The candidate list is empty only when a recipe book names no item for a station it requires.
     * {@code station} is set when the need exists only because a recipe requires that crafting station, so a caller
     * can decide whether the station can be made at all instead of gathering it like an ordinary raw item.
     */
    public record RawNeed(String label, Set<String> candidates, int count, Station station) {
        public RawNeed {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("a need must be named");
            }
            if (count <= 0) {
                throw new IllegalArgumentException("a need must be positive: " + count);
            }
            candidates = candidates == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(candidates));
        }

        /** An ordinary raw item, which no station requirement is attached to. */
        public RawNeed(String label, Set<String> candidates, int count) {
            this(label, candidates, count, null);
        }

        /** True when the need is a crafting station a recipe requires rather than material to gather. */
        public boolean stationNeed() {
            return station != null;
        }
    }

    /**
     * The raw items {@code targets} still need, in first-missing order, computed against one shared simulated
     * inventory: an intermediate crafted for one target is consumed by the next, and a material shared by several
     * targets appears once with the summed count. The input inventory is never mutated.
     */
    public static List<RawNeed> materialsFor(
            List<Target> targets, Map<String, Integer> inventory, RecipeBook book, Set<Station> stationsNearby) {
        return materialsFor(targets, inventory, book, stationsNearby, Set.of());
    }

    /**
     * The same demand without any recipe that runs at one of {@code unavailableStations}: the caller has found that
     * those stations cannot be made where the companion is, so a plan is asked for a route that avoids them.
     */
    public static List<RawNeed> materialsFor(
            List<Target> targets,
            Map<String, Integer> inventory,
            RecipeBook book,
            Set<Station> stationsNearby,
            Set<Station> unavailableStations) {
        Objects.requireNonNull(targets, "targets");
        if (targets.isEmpty() || targets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("targets must have 1 to " + MAX_TARGETS + " entries");
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
        Set<Station> unavailable = unavailableStations == null ? EnumSet.noneOf(Station.class) : unavailableStations;
        Map<String, Need> missing = new LinkedHashMap<>();
        for (Target target : targets) {
            Attempt attempt = new Search(target.item(), book, nearby, true, unavailable)
                    .need(target.item(), target.count(), 0, new LinkedHashSet<>(), sim);
            attempt.missing.forEach((label, need) -> missing.merge(label, need, Need::merge));
        }
        return missing.values().stream()
                .map(need -> new RawNeed(need.label, need.candidates, need.count, need.station))
                .toList();
    }

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
        return new CraftPlan(steps, missingCounts(attempt), stationsUsed, attempt.stationsCrafted);
    }

    /** The plan's published missing map, in the order the search first needed each label. */
    private static Map<String, Integer> missingCounts(Attempt attempt) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        attempt.missing.forEach((label, need) -> counts.put(label, need.count));
        return counts;
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
        final Set<Station> missingStations = EnumSet.noneOf(Station.class);

        Sim copy() {
            Sim copy = new Sim();
            copy.items.putAll(items);
            copy.reserved.addAll(reserved);
            copy.craftedStations.addAll(craftedStations);
            copy.missingStations.addAll(missingStations);
            return copy;
        }

        void adopt(Sim other) {
            items.clear();
            items.putAll(other.items);
            reserved.clear();
            reserved.addAll(other.reserved);
            craftedStations.clear();
            craftedStations.addAll(other.craftedStations);
            missingStations.clear();
            missingStations.addAll(other.missingStations);
        }
    }

    private static final class Search {
        private final String target;
        private final RecipeBook book;
        private final Set<Station> stationsNearby;
        private final Set<Station> unavailableStations;
        private final boolean materials;
        private final Map<String, Boolean> cycles = new HashMap<>();

        Search(String target, RecipeBook book, Set<Station> stationsNearby) {
            this(target, book, stationsNearby, false, Set.of());
        }

        Search(String target, RecipeBook book, Set<Station> stationsNearby, boolean materials) {
            this(target, book, stationsNearby, materials, Set.of());
        }

        Search(
                String target,
                RecipeBook book,
                Set<Station> stationsNearby,
                boolean materials,
                Set<Station> unavailableStations) {
            this.materials = materials;
            this.target = target;
            this.book = book;
            this.stationsNearby = stationsNearby;
            this.unavailableStations = unavailableStations;
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
            List<Sim> failedSims = new ArrayList<>();
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
                    failedSims.add(copy);
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
            int chosenIndex = 0;
            for (int i = 1; i < failed.size(); i++) {
                if (cheaperFailure(failed.get(i), failed.get(chosenIndex))) {
                    chosenIndex = i;
                }
            }
            Attempt chosen = failed.get(chosenIndex);
            if (materials) {
                // Its deficits are returned to the caller, so its consumed resources and reusable station
                // requirements belong to this branch too. Losing branches never publish either.
                sim.adopt(failedSims.get(chosenIndex));
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

        /**
         * Among failed branches, one that needs no station this plan may not use wins over one that does, however
         * few items the latter is short of: the caller has already decided those stations cannot be made here.
         */
        private static boolean cheaperFailure(Attempt candidate, Attempt best) {
            if (candidate.unavailableStations().size()
                    != best.unavailableStations().size()) {
                return candidate.unavailableStations().size()
                        < best.unavailableStations().size();
            }
            return candidate.missingTotal() < best.missingTotal();
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
            if (station == Station.INVENTORY
                    || stationsNearby.contains(station)
                    || sim.missingStations.contains(station)) {
                return new Attempt();
            }
            String item = book.stationItem(station);
            if (unavailableStations.contains(station)) {
                // A route this plan may not take: name the station itself so the caller learns which one the
                // rejected route needs, and mark the branch so one that avoids it is preferred.
                return Attempt.stationRequired(station, item);
            }
            if (item == null) {
                // A book that names no item for a non-inventory station leaves nothing to craft or carry.
                if (materials) {
                    sim.missingStations.add(station);
                }
                return Attempt.stationRequired(station, null);
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
                if (materials) {
                    // The returned deficit pays for one reusable station, independently of any consumed
                    // ingredient with the same label. This assumption is scoped to this simulation branch.
                    sim.missingStations.add(station);
                }
                // Every deficit of this attempt is a station requirement: making the station is why it exists.
                attempt.markStation(station);
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
            return Attempt.missing(ingredient.label(), ingredient.items(), 1);
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
     * Steps merged by recipe id (dependencies first), the missing ingredients, the total craft count, the stations
     * whose item this attempt crafted, and whether the attempt's own recipe needed a crafted station.
     */
    private static final class Attempt {
        private final Map<String, Integer> stepTimes = new LinkedHashMap<>();
        private final Map<String, Recipe> stepRecipes = new HashMap<>();
        private final Map<String, Need> missing = new LinkedHashMap<>();
        private final Set<Station> stationsCrafted = EnumSet.noneOf(Station.class);
        private final Set<Station> unavailableStations = EnumSet.noneOf(Station.class);
        private int crafts;
        private int stationPenalty;

        static Attempt missing(String item, int qty) {
            return missing(item, List.of(item), qty);
        }

        static Attempt missing(String label, Collection<String> candidates, int qty) {
            Attempt attempt = new Attempt();
            attempt.missing.put(label, new Need(label, new LinkedHashSet<>(candidates), qty, null));
            return attempt;
        }

        /** A station a rejected route needs: reported by its own item, and marked as an unavailable requirement. */
        static Attempt stationRequired(Station station, String item) {
            Attempt attempt = new Attempt();
            String label = item == null ? station.noun() : item;
            Collection<String> candidates = item == null ? List.of() : List.of(item);
            attempt.missing.put(label, new Need(label, new LinkedHashSet<>(candidates), 1, station));
            attempt.unavailableStations.add(station);
            return attempt;
        }

        boolean ok() {
            return missing.isEmpty();
        }

        int crafts() {
            return crafts;
        }

        int missingTotal() {
            return missing.values().stream().mapToInt(Need::count).sum();
        }

        /** The stations this branch needs even though the caller ruled them out. */
        Set<Station> unavailableStations() {
            return unavailableStations;
        }

        /** Marks every deficit as belonging to {@code station}; a need already named for another station keeps it. */
        void markStation(Station station) {
            missing.replaceAll((label, need) -> need.withStation(station));
        }

        void merge(Attempt other) {
            other.stepTimes.forEach((id, times) -> {
                stepTimes.merge(id, times, Integer::sum);
                stepRecipes.putIfAbsent(id, other.stepRecipes.get(id));
            });
            other.missing.forEach((label, need) -> missing.merge(label, need, Need::merge));
            crafts += other.crafts;
            stationsCrafted.addAll(other.stationsCrafted);
            unavailableStations.addAll(other.unavailableStations);
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

    /**
     * One missing ingredient as the batch resolver needs it: the message label, the ids that satisfy it in preference
     * order, and how many are wanted. Merging two needs of the same label unions their candidates and sums the count.
     */
    private record Need(String label, Set<String> candidates, int count, Station station) {
        Need {
            candidates = Collections.unmodifiableSet(new LinkedHashSet<>(candidates));
        }

        /** This need named for {@code value} unless another station requirement already named it. */
        Need withStation(Station value) {
            return station == null ? new Need(label, candidates, count, value) : this;
        }

        /**
         * The union of candidates and the summed count. The station survives only when both occurrences are
         * station requirements: a need that is also consumed as an ordinary ingredient is gathered like one.
         */
        Need merge(Need other) {
            Set<String> union = new LinkedHashSet<>(candidates);
            union.addAll(other.candidates);
            Station station = this.station != null && other.station != null ? this.station : null;
            return new Need(label, union, count + other.count, station);
        }
    }
}
