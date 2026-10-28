package dev.friendai.features.acquisition;

import dev.friendai.features.crafting.Ingredient;
import java.util.Optional;

/** Maps an ingredient label to the block tag a search should use when a member is not available locally. */
public final class IngredientFamilies {
    private IngredientFamilies() {}

    /**
     * The block tag to search when no member of {@code ingredient} is locally obtainable. Planks may come from any
     * log; a species-specific log tag keeps its own scope; everything else has no family search.
     */
    public static Optional<String> searchTag(Ingredient ingredient) {
        String label = ingredient.label();
        if (label.equals("#minecraft:planks")) {
            return Optional.of("#minecraft:logs");
        }
        if (label.equals("#minecraft:logs")) {
            return Optional.of(label);
        }
        if (label.startsWith("#minecraft:") && label.endsWith("_logs")) {
            return Optional.of(label);
        }
        return Optional.empty();
    }
}
