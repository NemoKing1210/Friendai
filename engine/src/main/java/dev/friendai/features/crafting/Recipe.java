package dev.friendai.features.crafting;

import java.util.List;

/**
 * One recipe: the item id and count it yields per craft, its slots (duplicates allowed: three planks are
 * three slots), the item each slot gives back after the craft (a milk bucket leaves an empty bucket, and an
 * empty string is a slot that keeps nothing), where it is executed, and how long a cooking recipe takes
 * (0 for non-cooking recipes).
 */
public record Recipe(
        String id,
        String output,
        int outputCount,
        List<Ingredient> ingredients,
        List<String> remainders,
        Station station,
        int cookTicks) {
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
        remainders = remainders == null ? List.of() : List.copyOf(remainders);
        if (!remainders.isEmpty() && remainders.size() != ingredients.size()) {
            throw new IllegalArgumentException("recipe remainders must match its slots: " + id);
        }
        if (station == null) {
            throw new IllegalArgumentException("recipe needs a station: " + id);
        }
        if (cookTicks < 0) {
            throw new IllegalArgumentException("recipe cook time must not be negative: " + id);
        }
    }

    /** A recipe that is crafted rather than cooked and returns nothing to the bag. */
    public Recipe(String id, String output, int outputCount, List<Ingredient> ingredients, Station station) {
        this(id, output, outputCount, ingredients, List.of(), station, 0);
    }

    /** A cooked recipe that returns nothing to the bag. */
    public Recipe(
            String id, String output, int outputCount, List<Ingredient> ingredients, Station station, int cookTicks) {
        this(id, output, outputCount, ingredients, List.of(), station, cookTicks);
    }

    /** True when any slot returns an item, so a batch of this recipe hands something back between crafts. */
    public boolean returnsAnything() {
        return remainders.stream().anyMatch(id -> !id.isEmpty());
    }
}
