package dev.friendai.crafting;

import java.util.List;

/**
 * One recipe: the item id and count it yields per craft, its slots (duplicates allowed: three planks are
 * three slots), where it is executed, and how long a cooking recipe takes (0 for non-cooking recipes).
 */
public record Recipe(
        String id, String output, int outputCount, List<Ingredient> ingredients, Station station, int cookTicks) {
    public Recipe {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("recipe needs an id");
        }
        if (output == null || output.isEmpty()) {
            throw new IllegalArgumentException("recipe needs an output");
        }
        if (outputCount <= 0) {
            throw new IllegalArgumentException("recipe output count must be positive: " + id);
        }
        ingredients = List.copyOf(ingredients);
        if (station == null) {
            throw new IllegalArgumentException("recipe needs a station: " + id);
        }
        if (cookTicks < 0) {
            throw new IllegalArgumentException("recipe cook time must not be negative: " + id);
        }
    }

    /** A recipe that is crafted rather than cooked. */
    public Recipe(String id, String output, int outputCount, List<Ingredient> ingredients, Station station) {
        this(id, output, outputCount, ingredients, station, 0);
    }
}
