package dev.friendai.features.crafting;

/** One planned craft: the recipe and how many times to run it. */
public record CraftStep(Recipe recipe, int times) {
    public CraftStep {
        if (recipe == null) {
            throw new IllegalArgumentException("craft step needs a recipe");
        }
        if (times <= 0) {
            throw new IllegalArgumentException("craft times must be positive: " + recipe.id());
        }
    }
}
