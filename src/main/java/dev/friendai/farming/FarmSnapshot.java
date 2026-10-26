package dev.friendai.farming;

import java.util.List;
import java.util.Map;

/** Everything the planner knows: the classified cells, what the companion carries and how it feels. */
public record FarmSnapshot(List<FieldCell> cells, Map<String, Integer> inventory, boolean hasHoe, int boneMeal,
        boolean chestSpace, FoodNeed foodNeed) {
    public FarmSnapshot {
        cells = List.copyOf(cells);
        inventory = Map.copyOf(inventory);
        for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
            if (entry.getValue() < 0) {
                throw new IllegalArgumentException("negative count for " + entry.getKey());
            }
        }
        if (boneMeal < 0) {
            throw new IllegalArgumentException("boneMeal must not be negative: " + boneMeal);
        }
        if (foodNeed == null) {
            throw new IllegalArgumentException("foodNeed must not be null");
        }
    }
}
