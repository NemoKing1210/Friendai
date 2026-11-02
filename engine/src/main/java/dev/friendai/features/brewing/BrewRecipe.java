package dev.friendai.features.brewing;

import java.util.ArrayList;
import java.util.List;

/**
 * A bounded, ordered chain from one potion to a target potion. The number of brewing operations is
 * {@link #operationCount(int)}: at most three bottles brew together, so a request for four bottles costs two
 * operations per step, and every operation spends one fuel use.
 */
public record BrewRecipe(String target, List<BrewStep> steps) {
    /** Bottle slots in a brewing stand. */
    public static final int BOTTLES_PER_OPERATION = 3;
    /** A vanilla brewing operation takes 400 ticks; the task waits this plus a bounded margin. */
    public static final int OPERATION_TICKS = 400;

    public BrewRecipe {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        target = target.strip().toLowerCase(java.util.Locale.ROOT);
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a recipe needs at least one step");
        }
    }

    /** How many stand operations one step costs when brewing {@code bottles} bottles (1..3 per operation). */
    public static int operationsPerStep(int bottles) {
        if (bottles < 1) {
            throw new IllegalArgumentException("bottles must be at least 1");
        }
        return (bottles + BOTTLES_PER_OPERATION - 1) / BOTTLES_PER_OPERATION;
    }

    /** Total stand operations for {@code bottles} bottles through this whole chain. */
    public int operationCount(int bottles) {
        return operationsPerStep(bottles) * steps.size();
    }

    /** Fuel uses for the whole chain; one operation spends one fuel use. */
    public int fuelUses(int bottles) {
        return operationCount(bottles);
    }

    /** The ingredients in execution order, one entry per operation. */
    public List<String> ingredients() {
        List<String> ingredients = new ArrayList<>(steps.size());
        for (BrewStep step : steps) {
            ingredients.add(step.ingredient());
        }
        return List.copyOf(ingredients);
    }

    /** The potion the chain starts from. */
    public String startPotion() {
        return steps.getFirst().fromPotion();
    }
}
