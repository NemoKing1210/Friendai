package dev.friendai.farming;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Turns a snapshot of a field into an ordered list of steps: what to harvest and replant, what to sow, what to till
 * and where to use bone meal. Pure and deterministic; the executor re-plans when the world disagrees.
 */
public final class FarmPlanner {
    /** At most this many cells are tilled in one plan. */
    static final int TILL_CAP = 16;

    private FarmPlanner() {}

    public static FarmPlan plan(FarmSnapshot snapshot, FarmGoal goal, CropBook crops) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(crops, "crops");
        List<FarmStep> steps = new ArrayList<>();
        Map<String, Integer> replants = new HashMap<>();
        Map<String, Double> expected = new HashMap<>();
        Map<String, Integer> represented = new HashMap<>();
        for (FieldCell cell : ofKind(snapshot, CellKind.GROWING)) {
            represented.merge(cell.crop(), 1, Integer::sum);
        }
        for (FieldCell cell : serpentine(ofKind(snapshot, CellKind.RIPE))) {
            steps.add(new FarmStep.Harvest(cell));
            represented.merge(cell.crop(), 1, Integer::sum);
            if (cell.seed() != null) {
                steps.add(new FarmStep.Replant(cell, cell.seed()));
                replants.merge(cell.seed(), 1, Integer::sum);
                crops.byCrop(cell.crop()).ifPresent(info -> expected.merge(cell.seed(), info.seedYield(), Double::sum));
            }
        }

        Map<String, Integer> free = new HashMap<>();
        Map<String, Integer> shortfall = new LinkedHashMap<>();
        for (String seed : replants.keySet().stream().sorted().toList()) {
            int have = snapshot.inventory().getOrDefault(seed, 0) + (int) Math.floor(expected.getOrDefault(seed, 0.0));
            if (have < replants.get(seed)) {
                shortfall.put(seed, replants.get(seed) - have);
            }
        }
        for (CropInfo info : crops.plantable()) {
            String seed = info.seed();
            int have = snapshot.inventory().getOrDefault(seed, 0) + (int) Math.floor(expected.getOrDefault(seed, 0.0));
            free.put(seed, Math.max(0, have - replants.getOrDefault(seed, 0) - goal.seedReserve()));
        }

        int unsown = 0;
        List<String> notes = new ArrayList<>();
        Comparator<CropInfo> preference = preference(snapshot.foodNeed(), represented, crops);
        Comparator<CropInfo> wanted = wanted(snapshot.foodNeed(), crops);
        // Picks a seed for one cell, spending the budget; null when none is free (the shortfall is recorded).
        Function<Soil, String> sow = soil -> {
            List<CropInfo> options = options(crops, goal, soil);
            if (options.isEmpty()) {
                return null;
            }
            CropInfo pick = options.stream().filter(info -> free.getOrDefault(info.seed(), 0) > 0)
                    .min(preference).orElse(null);
            if (pick == null) {
                shortfall.merge(options.stream().min(wanted).orElseThrow().seed(), 1, Integer::sum);
                return null;
            }
            free.merge(pick.seed(), -1, Integer::sum);
            represented.merge(pick.crop(), 1, Integer::sum);
            return pick.seed();
        };
        for (FieldCell cell : serpentine(ofKind(snapshot, CellKind.EMPTY_SOIL))) {
            String seed = sow.apply(cell.soil());
            if (seed == null) {
                unsown++;
            } else {
                steps.add(new FarmStep.Sow(cell, seed));
            }
        }
        if (goal.till()) {
            if (!snapshot.hasHoe()) {
                notes.add("missing hoe");
            } else {
                for (FieldCell cell : serpentine(ofKind(snapshot, CellKind.TILLABLE)).stream().limit(TILL_CAP).toList()) {
                    steps.add(new FarmStep.Till(cell));
                    String seed = sow.apply(Soil.FARMLAND);
                    if (seed == null) {
                        unsown++;
                    } else {
                        steps.add(new FarmStep.Sow(cell, seed));
                    }
                }
            }
        }
        if (goal.useBoneMeal()) {
            if (snapshot.boneMeal() <= 0) {
                notes.add("no bone meal");
            } else {
                List<FieldCell> growing = serpentine(ofKind(snapshot, CellKind.GROWING));
                growing.stream().sorted(Comparator.comparingInt(FieldCell::age).reversed()).limit(snapshot.boneMeal())
                        .forEach(cell -> steps.add(new FarmStep.Bonemeal(cell)));
            }
        }

        List<FarmStep> capped = new ArrayList<>(
                steps.size() > goal.maxSteps() ? steps.subList(0, goal.maxSteps()) : steps);
        shortfall.forEach((item, count) -> capped.add(new FarmStep.NeedSeeds(item, count)));
        return new FarmPlan(capped, unsown, notes);
    }

    /** Crops that may go on this soil: only the goal's crop when one is set. */
    private static List<CropInfo> options(CropBook crops, FarmGoal goal, Soil soil) {
        List<CropInfo> compatible = crops.plantable().stream().filter(info -> info.soil() == soil).toList();
        if (goal.crop() == null) {
            return compatible;
        }
        return compatible.stream()
                .filter(info -> info.seed().equals(goal.crop()) || info.crop().equals(goal.crop())).toList();
    }

    /** What to ask for when nothing is in stock: the most nourishing crop when hungry, else the first in book order. */
    private static Comparator<CropInfo> wanted(FoodNeed need, CropBook crops) {
        List<CropInfo> order = crops.plantable();
        Comparator<CropInfo> primary = need == FoodNeed.HIGH
                ? Comparator.comparingInt((CropInfo info) -> -info.foodValue())
                : (a, b) -> 0;
        return primary.thenComparingInt(order::indexOf);
    }

    /** Hungry: the most nourishing crop; otherwise the one least represented in the field. Ties keep book order. */
    private static Comparator<CropInfo> preference(FoodNeed need, Map<String, Integer> represented, CropBook crops) {
        List<CropInfo> order = crops.plantable();
        Comparator<CropInfo> primary = need == FoodNeed.HIGH
                ? Comparator.comparingInt((CropInfo info) -> -info.foodValue())
                : Comparator.comparingInt((CropInfo info) -> represented.getOrDefault(info.crop(), 0));
        return primary.thenComparingInt(order::indexOf);
    }

    static List<FieldCell> ofKind(FarmSnapshot snapshot, CellKind kind) {
        return snapshot.cells().stream().filter(cell -> cell.kind() == kind).toList();
    }

    /**
     * Rows by dz ascending; within a row dx ascending on even row positions and descending on odd ones, where the
     * position is the index of the row among the distinct dz values present. dy breaks ties.
     */
    static List<FieldCell> serpentine(List<FieldCell> cells) {
        List<Integer> rows = new ArrayList<>(new TreeSet<>(cells.stream().map(FieldCell::dz).toList()));
        Comparator<FieldCell> order = Comparator.<FieldCell>comparingInt(FieldCell::dz).thenComparing((a, b) -> {
            int dx = Integer.compare(a.dx(), b.dx());
            return rows.indexOf(a.dz()) % 2 == 0 ? dx : -dx;
        }).thenComparingInt(FieldCell::dy);
        return cells.stream().sorted(order).toList();
    }
}
