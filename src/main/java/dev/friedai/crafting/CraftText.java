package dev.friedai.crafting;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/** LLM-facing texts for the crafting tools; the formats live here so they stay unit-tested. */
public final class CraftText {
    private CraftText() {}

    /** {@code missing: 3 × #minecraft:logs, 2 × minecraft:iron_ingot}. */
    public static String missingText(Map<String, Integer> missing) {
        return missing.entrySet().stream()
                .map(entry -> entry.getValue() + " × " + entry.getKey())
                .collect(Collectors.joining(", ", "missing: ", ""));
    }

    /**
     * {@code can craft 1 minecraft:iron_pickaxe now: craft minecraft:stick (1×) in the inventory, then …} or
     * {@code can't craft 1 minecraft:iron_pickaxe yet; missing: …}.
     */
    public static String planText(CraftPlan plan, String target, int count) {
        if (!plan.ok()) {
            return "can't craft " + count + " " + target + " yet; " + missingText(plan.missing());
        }
        String header = "can craft " + count + " " + target + " now";
        if (plan.steps().isEmpty()) {
            return header;
        }
        StringJoiner steps = new StringJoiner(", then ");
        for (CraftStep step : plan.steps()) {
            steps.add(step.recipe().output() + " (" + step.times() + "×) " + step.recipe().station().phrase());
        }
        return header + ": craft " + steps;
    }

    /** {@code minecraft:iron_pickaxe ×1 at a crafting table from 3 × minecraft:iron_ingot, 2 × minecraft:stick}. */
    public static String recipeText(Recipe recipe) {
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (Ingredient ingredient : recipe.ingredients()) {
            groups.merge(ingredient.label(), 1, Integer::sum);
        }
        String from = groups.entrySet().stream()
                .map(entry -> entry.getValue() + " × " + entry.getKey())
                .collect(Collectors.joining(", "));
        String base = recipe.output() + " ×" + recipe.outputCount() + " " + recipe.station().phrase();
        return from.isEmpty() ? base : base + " from " + from;
    }
}
