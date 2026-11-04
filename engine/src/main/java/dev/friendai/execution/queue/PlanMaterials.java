package dev.friendai.execution.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the steps a plan still has to run will need from the bag, by item id.
 *
 * <p>Only steps whose argument names a thing to hold are counted: obtaining, crafting and smelting name an item;
 * mining names a block and reserves everything that block yields, because a stone step is where the cobblestone for
 * the tools comes from. A step whose arguments are malformed contributes nothing — a broken plan must not stop the
 * queue or silently unprotect a stack.
 */
public final class PlanMaterials {
    /** Above this, a step's count is a typo rather than a plan. */
    private static final int MAX_COUNT = 4096;

    /** Targets one {@code obtain_items} step may reserve; the tool itself accepts at most eight. */
    private static final int MAX_BATCH_ITEMS = 8;

    private PlanMaterials() {}

    /** Maps a block id to the item ids it yields, so a {@code mine_blocks} step reserves what it actually produces. */
    public interface ToItemIds {
        List<String> itemsFor(String blockId);
    }

    /** The item counts these steps still need, merged by id. */
    public static Map<String, Integer> needs(List<QueuedStep> steps, ToItemIds resolve) {
        if (steps == null || steps.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> needs = new LinkedHashMap<>();
        for (QueuedStep step : steps) {
            if (step == null) {
                continue;
            }
            switch (step.tool()) {
                case "obtain_item", "craft_item", "smelt_items" -> addItem(needs, step.args(), "item");
                case "obtain_items" -> addBatch(needs, step.args());
                case "mine_blocks" -> addBlock(needs, step, resolve);
                default -> {}
            }
        }
        return needs.isEmpty() ? Map.of() : Map.copyOf(needs);
    }

    private static void addItem(Map<String, Integer> needs, JsonObject args, String key) {
        String id = text(args, key);
        int count = count(args);
        if (id.isEmpty() || count == 0) {
            return;
        }
        needs.merge(id, count, Integer::sum);
    }

    /** A batch step names several targets; each entry is reserved like an {@code obtain_item} step's item. */
    private static void addBatch(Map<String, Integer> needs, JsonObject args) {
        if (args == null || !args.has("items") || !args.get("items").isJsonArray()) {
            return;
        }
        JsonArray items = args.getAsJsonArray("items");
        for (int i = 0; i < Math.min(items.size(), MAX_BATCH_ITEMS); i++) {
            if (items.get(i).isJsonObject()) {
                addItem(needs, items.get(i).getAsJsonObject(), "item");
            }
        }
    }

    private static void addBlock(Map<String, Integer> needs, QueuedStep step, ToItemIds resolve) {
        JsonObject args = step.args();
        String block = text(args, "block");
        if (block.isEmpty()) {
            return;
        }
        List<String> items;
        try {
            items = resolve == null ? List.of(block) : resolve.itemsFor(block);
        } catch (RuntimeException ignored) {
            items = List.of(block);
        }
        if (items == null || items.isEmpty()) {
            items = List.of(block);
        }
        int count = count(args);
        if (count == 0) {
            return;
        }
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                needs.merge(item.strip(), count, Integer::sum);
            }
        }
    }

    private static String text(JsonObject args, String key) {
        if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
            return "";
        }
        var value = args.get(key);
        return value.getAsJsonPrimitive().isString() ? value.getAsString().strip() : "";
    }

    /**
     * The step's count, one when absent, clamped so a typo cannot reserve a boxful of nothing. A count below one is
     * a broken step rather than a request for nothing, so it reserves nothing at all.
     */
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
