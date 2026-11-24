package dev.friendai.features.crafting;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.friendai.execution.queue.QueuedStep;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The carried ingredients the queued work will consume, by item id.
 *
 * <p>{@link dev.friendai.execution.queue.PlanMaterials} reserves the item a step names. A step that names
 * bread, however, will bake it from wheat, and a step that names cooked beef will cook raw beef: those
 * ingredients sit in the bag and a reflex or another task could spend them first. This resolves each
 * remaining step's output through the level's real recipes and reserves one level of ingredients, choosing
 * the recipe whose slots the companion actually carries. The output itself is not repeated here — the plan
 * materials already count it — and a step whose recipe nothing carried can fill contributes nothing, so a
 * stale or impossible plan never locks the bag.
 */
public final class PlanIngredients {
    /** Above this, a step's count is a typo rather than a plan. */
    private static final int MAX_COUNT = 4096;

    /** A plan with more remaining steps than this reserves nothing beyond the bound. */
    private static final int MAX_STEPS = 64;

    /** Targets one {@code obtain_items} step may reserve; the tool itself accepts at most eight. */
    private static final int MAX_BATCH_ITEMS = 8;

    private PlanIngredients() {}

    /** The item counts these steps will consume as ingredients, merged by id, within the reserve bounds. */
    public static Map<String, Integer> committed(List<QueuedStep> steps, RecipeBook recipes, Set<String> carried) {
        if (steps == null || steps.isEmpty() || recipes == null || carried == null || carried.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> needs = new LinkedHashMap<>();
        int scanned = 0;
        for (QueuedStep step : steps) {
            if (++scanned > MAX_STEPS) {
                break;
            }
            if (step == null) {
                continue;
            }
            switch (step.tool()) {
                case "obtain_item", "craft_item", "smelt_items" ->
                    add(needs, text(step.args(), "item"), count(step.args()), recipes, carried);
                case "obtain_items" -> addBatch(needs, step.args(), recipes, carried);
                default -> {}
            }
        }
        return needs.isEmpty() ? Map.of() : Map.copyOf(needs);
    }

    private static void addBatch(Map<String, Integer> needs, JsonObject args, RecipeBook recipes, Set<String> carried) {
        if (args == null || !args.has("items") || !args.get("items").isJsonArray()) {
            return;
        }
        JsonArray items = args.getAsJsonArray("items");
        for (int i = 0; i < Math.min(items.size(), MAX_BATCH_ITEMS); i++) {
            if (items.get(i).isJsonObject()) {
                JsonObject target = items.get(i).getAsJsonObject();
                add(needs, text(target, "item"), count(target), recipes, carried);
            }
        }
    }

    /** Reserves the first recipe for the output whose every slot a carried item can fill. */
    private static void add(
            Map<String, Integer> needs, String output, int count, RecipeBook recipes, Set<String> carried) {
        if (output.isEmpty() || count <= 0) {
            return;
        }
        List<Recipe> candidates;
        try {
            candidates = recipes.producing(output);
        } catch (RuntimeException malformed) {
            return;
        }
        if (candidates == null) {
            return;
        }
        for (Recipe recipe : candidates) {
            Map<String, Integer> slots = fill(recipe, carried);
            if (slots == null) {
                continue;
            }
            int crafts = (count + recipe.outputCount() - 1) / recipe.outputCount();
            slots.forEach((id, perCraft) ->
                    needs.merge(id, (int) Math.min(MAX_COUNT, (long) perCraft * crafts), (left, right) ->
                            (int) Math.min(MAX_COUNT, (long) left + right)));
            return;
        }
    }

    /**
     * One item id per slot, counted as often as the slot appears, or null when a slot accepts nothing the
     * companion carries or the recipe is larger than a reservation may record.
     */
    private static Map<String, Integer> fill(Recipe recipe, Set<String> carried) {
        if (recipe.ingredients().size() > MAX_BATCH_ITEMS) {
            return null;
        }
        Map<String, Integer> slots = new LinkedHashMap<>();
        for (Ingredient ingredient : recipe.ingredients()) {
            String chosen = null;
            for (String id : ingredient.items()) {
                if (carried.contains(id)) {
                    chosen = id;
                    break;
                }
            }
            if (chosen == null) {
                return null;
            }
            slots.merge(chosen, 1, Integer::sum);
        }
        return slots.isEmpty() ? null : slots;
    }

    private static String text(JsonObject args, String key) {
        if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
            return "";
        }
        var value = args.get(key);
        return value.getAsJsonPrimitive().isString() ? value.getAsString().strip() : "";
    }

    /** The step's count, one when absent, clamped like the plan materials reserve. */
    private static int count(JsonObject args) {
        if (args == null || !args.has("count")) {
            return 1;
        }
        var value = args.get("count");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return 1;
        }
        int count = value.getAsInt();
        return count < 1 ? 0 : Math.min(count, MAX_COUNT);
    }
}
