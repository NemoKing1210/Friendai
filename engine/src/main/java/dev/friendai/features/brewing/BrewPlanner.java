package dev.friendai.features.brewing;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Plans the remaining brewing steps for a target from the potion that is actually in the bottles. Starting at
 * {@code minecraft:water} runs the full chain; starting at an intermediate potion (for example
 * {@code minecraft:awkward}) skips the completed step, which is what makes a half-finished stand resumable without
 * repeating an operation.
 *
 * <p>Pure: a plan is a value and never touches a container, a recipe manager or a level. A start potion that is not
 * on the chain, or a target the catalog does not support, is empty — a typed refusal, not a guess.
 */
public final class BrewPlanner {
    private BrewPlanner() {}

    /** The remaining steps from {@code startPotion} to {@code target}, or empty when the start is not on the chain. */
    public static Optional<BrewRecipe> plan(String target, String startPotion) {
        Optional<BrewRecipe> full = BrewRecipes.find(target);
        if (full.isEmpty()) {
            return Optional.empty();
        }
        String start = startPotion == null ? "" : startPotion.strip().toLowerCase(Locale.ROOT);
        List<BrewStep> steps = full.get().steps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).fromPotion().equals(start)) {
                return Optional.of(new BrewRecipe(full.get().target(), steps.subList(i, steps.size())));
            }
        }
        return Optional.empty();
    }

    /** The full chain from water for a supported target. */
    public static Optional<BrewRecipe> plan(String target) {
        return plan(target, "minecraft:water");
    }
}
