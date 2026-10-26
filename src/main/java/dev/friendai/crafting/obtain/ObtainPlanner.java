package dev.friendai.crafting.obtain;

import dev.friendai.crafting.Ingredient;
import dev.friendai.crafting.Recipe;
import dev.friendai.crafting.RecipeBook;
import dev.friendai.crafting.Station;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Plans how to get an item from what is around: carried items first, then containers, crafting, smelting,
 * mining, hunting, harvesting, and exploring and mining when a resource is not nearby. Every option is tried
 * on a simulated inventory; the cheapest complete chain wins, and a partial source (two of three ingots in a
 * chest) is combined with another for the rest.
 */
public final class ObtainPlanner {
    /** How many nested obtain steps the search may chain. */
    public static final int MAX_DEPTH = 8;

    /** Items the planner will burn; the first obtainable one is used. */
    private static final List<String> FUELS = List.of(
            "minecraft:coal",
            "minecraft:charcoal",
            "minecraft:oak_planks",
            "minecraft:birch_planks",
            "minecraft:spruce_planks",
            "minecraft:jungle_planks",
            "minecraft:acacia_planks",
            "minecraft:dark_oak_planks",
            "minecraft:mangrove_planks",
            "minecraft:cherry_planks",
            "minecraft:bamboo_planks",
            "minecraft:crimson_planks",
            "minecraft:warped_planks");

    private ObtainPlanner() {}

    public static ObtainPlan plan(String target, int count, Map<String, Integer> inventory, RecipeBook recipes,
            SourceBook sources, WorldFacts world) {
        Objects.requireNonNull(target, "target");
        if (target.isEmpty()) {
            throw new IllegalArgumentException("target must not be empty");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive: " + count);
        }
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(world, "world");
        Sim sim = new Sim();
        if (inventory != null) {
            inventory.forEach((item, amount) -> {
                if (item != null && amount != null && amount > 0) {
                    sim.items.put(item, amount);
                }
            });
        }
        world.containerItems().forEach((item, amount) -> {
            if (item != null && amount != null && amount > 0) {
                sim.containers.put(item, amount);
            }
        });
        Attempt attempt = new Search(recipes, sources, world)
                .need(target, count, 0, new LinkedHashSet<>(), sim, true);
        List<ObtainStep> steps = merge(attempt.steps);
        return new ObtainPlan(steps, attempt.missing, steps.size(), attempt.conditional);
    }

    /** Merges consecutive steps that do the same thing. */
    private static List<ObtainStep> merge(List<ObtainStep> steps) {
        List<ObtainStep> merged = new ArrayList<>(steps.size());
        for (ObtainStep step : steps) {
            if (!merged.isEmpty() && mergeable(merged.get(merged.size() - 1), step)) {
                merged.set(merged.size() - 1, combine(merged.get(merged.size() - 1), step));
            } else {
                merged.add(step);
            }
        }
        return merged;
    }

    private static boolean mergeable(ObtainStep left, ObtainStep right) {
        if (left instanceof ObtainStep.Take a && right instanceof ObtainStep.Take b) {
            return a.item().equals(b.item());
        }
        if (left instanceof ObtainStep.Craft a && right instanceof ObtainStep.Craft b) {
            return a.item().equals(b.item());
        }
        if (left instanceof ObtainStep.Smelt a && right instanceof ObtainStep.Smelt b) {
            return a.input().equals(b.input()) && a.output().equals(b.output());
        }
        if (left instanceof ObtainStep.Mine a && right instanceof ObtainStep.Mine b) {
            return a.block().equals(b.block()) && a.tool().equals(b.tool());
        }
        if (left instanceof ObtainStep.Hunt a && right instanceof ObtainStep.Hunt b) {
            return a.mob().equals(b.mob());
        }
        if (left instanceof ObtainStep.Harvest a && right instanceof ObtainStep.Harvest b) {
            return a.crop().equals(b.crop());
        }
        if (left instanceof ObtainStep.Shear a && right instanceof ObtainStep.Shear b) {
            return a.mob().equals(b.mob());
        }
        return left instanceof ObtainStep.Milk && right instanceof ObtainStep.Milk;
    }

    private static ObtainStep combine(ObtainStep left, ObtainStep right) {
        if (left instanceof ObtainStep.Take a && right instanceof ObtainStep.Take b) {
            return new ObtainStep.Take(a.item(), a.count() + b.count());
        }
        if (left instanceof ObtainStep.Craft a && right instanceof ObtainStep.Craft b) {
            return new ObtainStep.Craft(a.item(), a.count() + b.count());
        }
        if (left instanceof ObtainStep.Smelt a && right instanceof ObtainStep.Smelt b) {
            return new ObtainStep.Smelt(a.input(), a.count() + b.count(), a.output());
        }
        if (left instanceof ObtainStep.Mine a && right instanceof ObtainStep.Mine b) {
            return new ObtainStep.Mine(a.block(), a.count() + b.count(), a.tool());
        }
        if (left instanceof ObtainStep.Hunt a && right instanceof ObtainStep.Hunt b) {
            return new ObtainStep.Hunt(a.mob(), a.count() + b.count());
        }
        if (left instanceof ObtainStep.Harvest a && right instanceof ObtainStep.Harvest b) {
            return new ObtainStep.Harvest(a.count() + b.count(), a.crop());
        }
        if (left instanceof ObtainStep.Shear a && right instanceof ObtainStep.Shear b) {
            return new ObtainStep.Shear(a.mob(), a.count() + b.count());
        }
        return new ObtainStep.Milk(((ObtainStep.Milk) left).count() + ((ObtainStep.Milk) right).count());
    }

    /** The simulated world of one search branch. */
    private static final class Sim {
        final Map<String, Integer> items = new LinkedHashMap<>();
        final Map<String, Integer> containers = new LinkedHashMap<>();
        final Set<String> reserved = new LinkedHashSet<>();
        final Set<Long> reservedSites = new LinkedHashSet<>();
        final Map<Long, Integer> siteBlocks = new LinkedHashMap<>();

        Sim copy() {
            Sim copy = new Sim();
            copy.items.putAll(items);
            copy.containers.putAll(containers);
            copy.reserved.addAll(reserved);
            copy.reservedSites.addAll(reservedSites);
            copy.siteBlocks.putAll(siteBlocks);
            return copy;
        }

        void adopt(Sim other) {
            items.clear();
            items.putAll(other.items);
            containers.clear();
            containers.putAll(other.containers);
            reserved.clear();
            reserved.addAll(other.reserved);
            reservedSites.clear();
            reservedSites.addAll(other.reservedSites);
            siteBlocks.clear();
            siteBlocks.putAll(other.siteBlocks);
        }

        int available(String item) {
            return reserved.contains(item) ? 0 : items.getOrDefault(item, 0);
        }

        int take(String item, int qty) {
            int available = available(item);
            int taken = Math.min(available, qty);
            if (taken > 0) {
                int left = available - taken;
                if (left == 0) {
                    items.remove(item);
                } else {
                    items.put(item, left);
                }
            }
            return taken;
        }

        void add(String item, int qty) {
            if (qty > 0) {
                items.merge(item, qty, Integer::sum);
            }
        }
    }

    /** Steps and missing items produced while obtaining something, with the search cost used to rank them. */
    private static final class Attempt {
        final List<ObtainStep> steps = new ArrayList<>();
        final Map<String, Integer> missing = new LinkedHashMap<>();
        int cost;
        boolean conditional;

        boolean ok() {
            return missing.isEmpty();
        }

        void add(ObtainStep step) {
            steps.add(step);
        }

        void merge(Attempt other) {
            steps.addAll(other.steps);
            other.missing.forEach((item, qty) -> missing.merge(item, qty, Integer::sum));
            cost += other.cost;
            conditional |= other.conditional;
        }
    }

    private interface Provider {
        int capacity(Sim sim, int want);

        Candidate take(Search search, Sim sim, int want, int depth, Set<String> path, boolean emitCraft);
    }

    private record Candidate(Provider provider, Attempt attempt, Sim sim, int supplied) {
        int cost() {
            return attempt.cost;
        }
    }

    private static final class Search {
        private final RecipeBook book;
        private final SourceBook sources;
        private final WorldFacts world;

        Search(RecipeBook book, SourceBook sources, WorldFacts world) {
            this.book = book;
            this.sources = sources;
            this.world = world;
        }

        /**
         * Obtains {@code qty} of {@code item} from {@code sim}. A craft step of this call is emitted only when
         * {@code emitCraft} is true: the ingredient recursion of a craft leaves its purely crafting
         * intermediates to the crafting task.
         */
        Attempt need(String item, int qty, int depth, Set<String> path, Sim sim, boolean emitCraft) {
            Attempt total = new Attempt();
            qty -= sim.take(item, qty);
            if (qty <= 0) {
                return total;
            }
            if (depth > MAX_DEPTH || path.contains(item)) {
                total.missing.merge(item, qty, Integer::sum);
                return total;
            }
            Set<String> nextPath = new LinkedHashSet<>(path);
            nextPath.add(item);
            List<Provider> pool = new ArrayList<>(providers(item, sim));
            while (qty > 0) {
                Candidate best = null;
                for (Provider provider : pool) {
                    int capacity = Math.min(provider.capacity(sim, qty), qty);
                    if (capacity <= 0) {
                        continue;
                    }
                    Sim copy = sim.copy();
                    Candidate candidate = provider.take(this, copy, capacity, depth, nextPath, emitCraft);
                    if (candidate != null && (candidate.supplied() > 0 || candidate.attempt().conditional)
                            && (best == null || cheaper(candidate, best))) {
                        best = candidate;
                    }
                }
                if (best == null) {
                    total.missing.merge(item, qty, Integer::sum);
                    return total;
                }
                sim.adopt(best.sim());
                total.merge(best.attempt());
                if (best.attempt().conditional) {
                    total.conditional = true;
                    if (total.missing.isEmpty()) total.missing.merge(item, qty, Integer::sum);
                    return total;
                }
                qty -= best.supplied();
                pool.remove(best.provider());
            }
            return total;
        }

        private static boolean cheaper(Candidate candidate, Candidate best) {
            if (candidate.attempt().conditional != best.attempt().conditional) return !candidate.attempt().conditional;
            if (candidate.attempt().conditional) {
                ObtainStep.VisitResource a = firstVisit(candidate.attempt());
                ObtainStep.VisitResource b = firstVisit(best.attempt());
                if (candidate.cost() != best.cost()) return candidate.cost() < best.cost();
                if (a != null && b != null) {
                    int site = Long.compare(a.siteId(), b.siteId());
                    if (site != 0) return site < 0;
                    return a.block().compareTo(b.block()) < 0;
                }
                return false;
            }
            long candidateUnit = (long) candidate.cost() * 1000 / candidate.supplied();
            long bestUnit = (long) best.cost() * 1000 / best.supplied();
            if (candidateUnit != bestUnit) {
                return candidateUnit < bestUnit;
            }
            if (candidate.cost() != best.cost()) {
                return candidate.cost() < best.cost();
            }
            if (candidate.attempt().steps.size() != best.attempt().steps.size()) {
                return candidate.attempt().steps.size() < best.attempt().steps.size();
            }
            return false;
        }

        private static ObtainStep.VisitResource firstVisit(Attempt attempt) {
            return attempt.steps.stream().filter(ObtainStep.VisitResource.class::isInstance)
                    .map(ObtainStep.VisitResource.class::cast).findFirst().orElse(null);
        }

        private List<Provider> providers(String item, Sim sim) {
            List<Provider> providers = new ArrayList<>();
            if (sim.containers.getOrDefault(item, 0) > 0) {
                providers.add(new ContainerProvider(item));
            }
            for (Recipe recipe : book.producing(item)) {
                if (recipe.station().isCooking()) {
                    providers.add(new SmeltProvider(recipe));
                } else {
                    providers.add(new CraftProvider(recipe));
                }
            }
            for (Source source : sources.sourcesOf(item)) {
                switch (source) {
                    case Source.Mine mine -> {
                        if (world.blocksNearby().getOrDefault(mine.block(), 0) > 0) {
                            providers.add(new MineProvider(item, mine, false));
                        }
                        if (world.exploreAllowed()) {
                            world.resourceCandidates().stream()
                                    .filter(candidate -> !candidate.visibleNow() && candidate.blockId().equals(mine.block()))
                                    .filter(candidate -> !world.excludedSiteIds().contains(candidate.siteId()))
                                    .sorted(java.util.Comparator.comparingLong(ResourceCandidate::siteId)
                                            .thenComparing(ResourceCandidate::blockId))
                                    .forEach(candidate -> providers.add(new VisitProvider(item, mine, candidate)));
                        }
                        if (world.exploreAllowed() && explorable(item, mine)) {
                            boolean hasRememberedSite = world.resourceCandidates().stream()
                                    .anyMatch(candidate -> !candidate.visibleNow() && candidate.blockId().equals(mine.block())
                                            && !world.excludedSiteIds().contains(candidate.siteId()));
                            if (!hasRememberedSite) providers.add(new MineProvider(item, mine, true));
                        }
                    }
                    case Source.Hunt hunt -> providers.add(new HuntProvider(hunt));
                    case Source.Harvest harvest -> providers.add(new HarvestProvider(harvest));
                    case Source.Shear shear -> providers.add(new ShearProvider(shear));
                    case Source.Milk milk -> providers.add(new MilkProvider(item, milk));
                }
            }
            return providers;
        }

        private boolean explorable(String item, Source.Mine mine) {
            if (mine.surface()) {
                return true;
            }
            return world.dimensionHasOre() && OreDepths.depthFor(item).isPresent();
        }

        /** True when a carried or reserved item satisfies the tool need. */
        private boolean carriedTool(ToolNeed need, Sim sim) {
            if (need.kind() == ToolKind.HAND) {
                return true;
            }
            for (Map.Entry<String, Integer> entry : sim.items.entrySet()) {
                if (entry.getValue() > 0 && !sim.reserved.contains(entry.getKey())
                        && need.satisfiedBy(sources.toolOf(entry.getKey()))) {
                    return true;
                }
            }
            for (String item : sim.reserved) {
                if (need.satisfiedBy(sources.toolOf(item))) {
                    return true;
                }
            }
            return false;
        }

        /** Makes sure a tool satisfying the need is available, planning it when it is not carried. */
        private boolean ensureTool(ToolNeed need, int depth, Set<String> path, Sim sim, Attempt attempt) {
            if (carriedTool(need, sim)) {
                return true;
            }
            String item = sources.toolItem(need).or(() -> Optional.ofNullable(need.vanillaItem())).orElse(null);
            if (item == null) {
                return false;
            }
            // A tool is its own chain: a gold trip may need an iron pickaxe whose chain is as long as any
            // other, and the shared path already guards real cycles.
            Attempt tool = need(item, 1, 0, path, sim, true);
            if (tool.conditional) {
                attempt.merge(tool);
                return false;
            }
            if (!tool.ok()) {
                return false;
            }
            attempt.merge(tool);
            sim.add(item, 1);
            sim.reserved.add(item);
            return true;
        }

        /** Makes sure a station is usable: nearby, carried, or planned and then reserved. */
        private boolean ensureStation(Station station, int depth, Set<String> path, Sim sim, Attempt attempt) {
            if (station == Station.INVENTORY || stationNearby(station)) {
                return true;
            }
            String item = book.stationItem(station);
            if (item == null) {
                return false;
            }
            if (sim.reserved.contains(item) || sim.available(item) > 0) {
                sim.reserved.add(item);
                return true;
            }
            Attempt planned = craftable(item, 1, 0, path, sim);
            if (!planned.ok()) {
                return false;
            }
            attempt.merge(planned);
            sim.add(item, 1);
            sim.reserved.add(item);
            return true;
        }

        /**
         * Obtains a station item by crafting or carrying it, never by mining a placed one: a table taken from
         * the world is infrastructure the rest of the plan is counting on.
         */
        private Attempt craftable(String item, int qty, int depth, Set<String> path, Sim sim) {
            Attempt total = new Attempt();
            qty -= sim.take(item, qty);
            if (qty <= 0) {
                return total;
            }
            if (depth > MAX_DEPTH || path.contains(item)) {
                total.missing.merge(item, qty, Integer::sum);
                return total;
            }
            Set<String> nextPath = new LinkedHashSet<>(path);
            nextPath.add(item);
            Attempt best = null;
            Sim bestSim = null;
            for (Recipe recipe : book.producing(item)) {
                if (recipe.station().isCooking()) {
                    continue;
                }
                Sim copy = sim.copy();
                Candidate candidate = new CraftProvider(recipe).take(this, copy, qty, depth, nextPath, true);
                if (candidate != null && candidate.supplied() > 0
                        && (best == null || candidate.attempt().cost < best.cost)) {
                    best = candidate.attempt();
                    bestSim = copy;
                }
            }
            if (best == null) {
                total.missing.merge(item, qty, Integer::sum);
                return total;
            }
            sim.adopt(bestSim);
            return best;
        }

        private boolean stationNearby(Station station) {
            return world.stationsNearby().contains(station.noun())
                    || world.stationsNearby().contains(station.name());
        }

        /** Plans one cheap fuel item per eight smelted inputs. */
        private boolean addFuel(Sim sim, Attempt attempt, int inputs, int depth, Set<String> path) {
            int count = Math.max(1, (inputs + 7) / 8);
            Attempt best = null;
            Sim bestSim = null;
            for (String fuel : FUELS) {
                Sim copy = sim.copy();
                Attempt planned = need(fuel, count, depth + 1, path, copy, true);
                if (planned.ok() && (best == null || planned.cost < best.cost)) {
                    best = planned;
                    bestSim = copy;
                }
            }
            if (best == null) {
                return false;
            }
            attempt.merge(best);
            sim.adopt(bestSim);
            return true;
        }

        private static String firstCarried(Ingredient ingredient, Sim sim) {
            for (String id : ingredient.items()) {
                if (sim.available(id) > 0) {
                    return id;
                }
            }
            return null;
        }

        private boolean fillIngredient(Ingredient ingredient, int qty, int depth, Set<String> path, Sim sim,
                Attempt attempt) {
            for (String id : ingredient.items()) {
                if (sim.available(id) > 0) {
                    qty -= sim.take(id, qty);
                    if (qty == 0) {
                        return true;
                    }
                }
            }
            for (String id : ingredient.items()) {
                Sim copy = sim.copy();
                Attempt planned = need(id, qty, depth + 1, path, copy, false);
                if (planned.conditional) {
                    attempt.merge(planned);
                    sim.adopt(copy);
                    return false;
                }
                if (planned.ok()) {
                    attempt.merge(planned);
                    sim.adopt(copy);
                    return true;
                }
            }
            return false;
        }

        private final class ContainerProvider implements Provider {
            private final String item;

            ContainerProvider(String item) {
                this.item = item;
            }

            @Override
            public int capacity(Sim sim, int want) {
                return Math.min(want, sim.containers.getOrDefault(item, 0));
            }

            @Override
            public Candidate take(Search search, Sim sim, int want, int depth, Set<String> path, boolean emitCraft) {
                int taken = Math.min(want, sim.containers.getOrDefault(item, 0));
                if (taken <= 0) {
                    return null;
                }
                int left = sim.containers.get(item) - taken;
                if (left <= 0) {
                    sim.containers.remove(item);
                } else {
                    sim.containers.put(item, left);
                }
                Attempt attempt = new Attempt();
                attempt.add(new ObtainStep.Take(item, taken));
                attempt.cost += (taken + 63) / 64;
                return new Candidate(this, attempt, sim, taken);
            }
        }

        private final class CraftProvider implements Provider {
            private final Recipe recipe;

            CraftProvider(Recipe recipe) {
                this.recipe = recipe;
            }

            @Override
            public int capacity(Sim sim, int want) {
                return want;
            }

            @Override
            public Candidate take(Search search, Sim sim, int want, int depth, Set<String> path, boolean emitCraft) {
                int times = (want + recipe.outputCount() - 1) / recipe.outputCount();
                Attempt attempt = new Attempt();
                Map<Ingredient, Integer> slots = new LinkedHashMap<>();
                for (Ingredient ingredient : recipe.ingredients()) {
                    slots.merge(ingredient, 1, Integer::sum);
                }
                for (int craft = 0; craft < times; craft++) {
                    for (Map.Entry<Ingredient, Integer> slot : slots.entrySet()) {
                        if (!fillIngredient(slot.getKey(), slot.getValue(), depth, path, sim, attempt)) {
                            if (attempt.conditional) return new Candidate(this, attempt, sim, 0);
                            return null;
                        }
                    }
                }
                if (!ensureStation(recipe.station(), depth, path, sim, attempt)) {
                    return null;
                }
                int leftover = times * recipe.outputCount() - want;
                if (leftover > 0) {
                    sim.add(recipe.output(), leftover);
                }
                if (emitCraft) {
                    attempt.add(new ObtainStep.Craft(recipe.output(), want));
                }
                attempt.cost += 2 * times;
                return new Candidate(this, attempt, sim, want);
            }
        }

        private final class SmeltProvider implements Provider {
            private final Recipe recipe;

            SmeltProvider(Recipe recipe) {
                this.recipe = recipe;
            }

            @Override
            public int capacity(Sim sim, int want) {
                return want;
            }

            @Override
            public Candidate take(Search search, Sim sim, int want, int depth, Set<String> path, boolean emitCraft) {
                int times = (want + recipe.outputCount() - 1) / recipe.outputCount();
                if (recipe.ingredients().isEmpty()) {
                    return null;
                }
                Ingredient input = recipe.ingredients().get(0);
                Attempt attempt = new Attempt();
                String inputItem = null;
                for (String id : input.items()) {
                    Sim copy = sim.copy();
                    Attempt planned = need(id, times, depth + 1, path, copy, true);
                    if (planned.conditional) {
                        attempt.merge(planned);
                        sim.adopt(copy);
                        return new Candidate(this, attempt, sim, 0);
                    }
                    if (planned.ok()) {
                        attempt.merge(planned);
                        sim.adopt(copy);
                        inputItem = id;
                        break;
                    }
                }
                if (inputItem == null || !addFuel(sim, attempt, times, depth, path)
                        || !ensureStation(recipe.station(), depth, path, sim, attempt)) {
                    return null;
                }
                int leftover = times * recipe.outputCount() - want;
                if (leftover > 0) {
                    sim.add(recipe.output(), leftover);
                }
                attempt.add(new ObtainStep.Smelt(inputItem, times, recipe.output()));
                attempt.cost += 3 * times;
                return new Candidate(this, attempt, sim, want);
            }
        }

        private final class MineProvider implements Provider {
            private final String item;
            private final Source.Mine source;
            private final boolean explore;

            MineProvider(String item, Source.Mine source, boolean explore) {
                this.item = item;
                this.source = source;
                this.explore = explore;
            }

            @Override
            public int capacity(Sim sim, int want) {
                if (explore) {
                    return want;
                }
                int nearby = world.blocksNearby().getOrDefault(source.block(), 0);
                if (nearby <= 0) {
                    return 0;
                }
                return Math.min(want, Math.max(1, (int) Math.floor(nearby * source.perBlock())));
            }

            @Override
            public Candidate take(Search search, Sim sim, int want, int depth, Set<String> path, boolean emitCraft) {
                int blocks = Math.max(1, (int) Math.ceil(want / source.perBlock()));
                Attempt attempt = new Attempt();
                if (!ensureTool(source.tool(), depth, path, sim, attempt)) {
                    if (attempt.conditional) return new Candidate(this, attempt, sim, 0);
                    return null;
                }
                if (explore) {
                    int depthY = 0;
                    if (!source.surface()) {
                        OptionalInt ore = OreDepths.depthFor(item);
                        if (ore.isEmpty()) {
                            return null;
                        }
                        depthY = ore.getAsInt();
                    }
                    attempt.add(new ObtainStep.Explore(source.block(), !source.surface(), depthY));
                    attempt.cost += 20;
                }
                attempt.add(new ObtainStep.Mine(source.block(), blocks, source.tool()));
                attempt.cost += 4 * blocks;
                return new Candidate(this, attempt, sim, want);
            }
        }

        private final class VisitProvider implements Provider {
            private final String item;
            private final Source.Mine source;
            private final ResourceCandidate resource;

            VisitProvider(String item, Source.Mine source, ResourceCandidate resource) {
                this.item = item;
                this.source = source;
                this.resource = resource;
            }

            @Override
            public int capacity(Sim sim, int want) {
                if (sim.reservedSites.contains(resource.siteId())) return 0;
                int availableBlocks = sim.siteBlocks.computeIfAbsent(resource.siteId(), ignored ->
                        world.resourceCandidates().stream().filter(candidate -> candidate.siteId() == resource.siteId())
                                .mapToInt(ResourceCandidate::lastObservedBlocks).max().orElse(0));
                if (availableBlocks < 1) return 0;
                return Math.min(want, Math.max(1, (int) Math.floor(availableBlocks * source.perBlock())));
            }

            @Override
            public Candidate take(Search search, Sim sim, int want, int depth, Set<String> path, boolean emitCraft) {
                int availableBlocks = sim.siteBlocks.getOrDefault(resource.siteId(), resource.lastObservedBlocks());
                int blocks = Math.min(availableBlocks, Math.max(1, (int) Math.ceil(want / source.perBlock())));
                if (blocks < 1) return null;
                sim.siteBlocks.put(resource.siteId(), availableBlocks - blocks);
                sim.reservedSites.add(resource.siteId());
                Attempt attempt = new Attempt();
                attempt.add(new ObtainStep.VisitResource(resource.siteId(), resource.dimension(), source.block()));
                attempt.conditional = true;
                attempt.cost += (int) Math.ceil(resource.estimatedCost());
                return new Candidate(this, attempt, sim, 0);
            }
        }

        private final class HuntProvider implements Provider {
            private final Source.Hunt source;

            HuntProvider(Source.Hunt source) {
                this.source = source;
            }

            @Override
            public int capacity(Sim sim, int want) {
                int mobs = world.mobsNearby().getOrDefault(source.mob(), 0);
                if (mobs <= 0) {
                    return 0;
                }
                return Math.min(want, Math.max(1, (int) Math.floor(mobs * source.perKill())));
            }

            @Override
            public Candidate take(Search search, Sim sim, int want, int depth, Set<String> path, boolean emitCraft) {
                int kills = Math.max(1, (int) Math.ceil(want / source.perKill()));
                Attempt attempt = new Attempt();
                attempt.add(new ObtainStep.Hunt(source.mob(), kills));
                attempt.cost += 6 * kills;
                return new Candidate(this, attempt, sim, want);
            }
        }

        private final class HarvestProvider implements Provider {
            private final Source.Harvest source;

            HarvestProvider(Source.Harvest source) {
                this.source = source;
            }

            @Override
            public int capacity(Sim sim, int want) {
                int crops = world.matureCrops().getOrDefault(source.crop(), 0);
                if (crops <= 0) {
                    return 0;
                }
                return Math.min(want, Math.max(1, (int) Math.floor(crops * source.perBlock())));
            }

            @Override
            public Candidate take(Search search, Sim sim, int want, int depth, Set<String> path, boolean emitCraft) {
                int blocks = Math.max(1, (int) Math.ceil(want / source.perBlock()));
                Attempt attempt = new Attempt();
                attempt.add(new ObtainStep.Harvest(blocks, source.crop()));
                attempt.cost += 4 * blocks;
                return new Candidate(this, attempt, sim, want);
            }
        }

        private final class ShearProvider implements Provider {
            private final Source.Shear source;

            ShearProvider(Source.Shear source) {
                this.source = source;
            }

            @Override
            public int capacity(Sim sim, int want) {
                int mobs = world.mobsNearby().getOrDefault(source.mob(), 0);
                if (mobs <= 0) {
                    return 0;
                }
                return Math.min(want, Math.max(1, (int) Math.floor(mobs * source.perAnimal())));
            }

            @Override
            public Candidate take(Search search, Sim sim, int want, int depth, Set<String> path, boolean emitCraft) {
                int animals = Math.max(1, (int) Math.ceil(want / source.perAnimal()));
                Attempt attempt = new Attempt();
                attempt.add(new ObtainStep.Shear(source.mob(), animals));
                attempt.cost += 4 * animals;
                return new Candidate(this, attempt, sim, want);
            }
        }

        private final class MilkProvider implements Provider {
            private final String item;
            private final Source.Milk source;

            MilkProvider(String item, Source.Milk source) {
                this.item = item;
                this.source = source;
            }

            @Override
            public int capacity(Sim sim, int want) {
                return Math.min(want, world.mobsNearby().getOrDefault(source.mob(), 0));
            }

            @Override
            public Candidate take(Search search, Sim sim, int want, int depth, Set<String> path, boolean emitCraft) {
                Attempt attempt = new Attempt();
                Attempt buckets = need("minecraft:bucket", want, depth + 1, path, sim, true);
                if (!buckets.ok()) {
                    return null;
                }
                attempt.merge(buckets);
                attempt.add(new ObtainStep.Milk(want));
                attempt.cost += 4 * want;
                return new Candidate(this, attempt, sim, want);
            }
        }
    }
}
