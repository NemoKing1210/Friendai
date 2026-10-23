package dev.friedai.crafting;

import java.util.List;

/**
 * One recipe: the item id and count it yields per craft, its slots (duplicates allowed: three planks are
 * three slots) and where it is executed.
 */
public record Recipe(String id, String output, int outputCount, List<Ingredient> ingredients, Station station) {
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
    }
}
