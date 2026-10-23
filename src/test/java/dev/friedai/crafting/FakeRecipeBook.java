package dev.friedai.crafting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test recipe book: recipes are added in order and returned per output in that order. Station items are
 * the three vanilla stations. Subclasses can build richer fixtures on top of {@link #add(Recipe)}.
 */
class FakeRecipeBook implements RecipeBook {
    private static final Map<Station, String> STATION_ITEMS = Map.of(
            Station.CRAFTING_TABLE, "minecraft:crafting_table",
            Station.STONECUTTER, "minecraft:stonecutter",
            Station.SMITHING_TABLE, "minecraft:smithing_table");

    private final Map<String, List<Recipe>> byOutput = new LinkedHashMap<>();

    FakeRecipeBook add(Recipe recipe) {
        byOutput.computeIfAbsent(recipe.output(), output -> new ArrayList<>()).add(recipe);
        return this;
    }

    @Override
    public List<Recipe> producing(String item) {
        return List.copyOf(byOutput.getOrDefault(item, List.of()));
    }

    @Override
    public String stationItem(Station station) {
        return STATION_ITEMS.get(station);
    }
}
