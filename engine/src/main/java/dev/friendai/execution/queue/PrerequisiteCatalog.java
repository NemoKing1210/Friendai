package dev.friendai.execution.queue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature-owned prerequisites for the queue actions that have a definite, checkable one. A tool with no entry has no
 * declared prerequisite, not "unknown"; the catalog never guesses from names. Counts, radii and other amounts are
 * ignored when they do not change the prerequisite.
 */
public final class PrerequisiteCatalog {
    /** How close a work station must be to count as usable for a plan action. */
    public static final int STATION_RADIUS = 16;

    private PrerequisiteCatalog() {}

    public static List<PlanRepair.Prerequisite> forAction(String tool, JsonObject args) {
        if (tool == null || tool.isBlank()) return List.of();
        List<PlanRepair.Prerequisite> out = new ArrayList<>();
        switch (tool) {
            case "smelt_items" -> out.add(new PlanRepair.Station("minecraft:furnace", STATION_RADIUS));
            case "brew_potion" -> out.add(new PlanRepair.Station("minecraft:brewing_stand", STATION_RADIUS));
            case "place_block" -> {
                String id = first(args, "block", "item");
                if (!id.isBlank()) out.add(new PlanRepair.Item(id, 1, "obtain_item"));
            }
            default -> {
                // No declared prerequisite.
            }
        }
        return List.copyOf(out);
    }

    private static String first(JsonObject args, String... keys) {
        if (args == null) return "";
        for (String key : keys) {
            JsonElement value = args.get(key);
            if (value != null && value.isJsonPrimitive() && !value.getAsString().isBlank()) {
                return value.getAsString();
            }
        }
        return "";
    }
}
