package dev.friendai.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test recipe book: recipes are added in order and returned per output in that order. Station items are
 * the three vanilla stations. Subclasses can build richer fixtures on top of {@link #add(Recipe)}.
 */
public class FakeRecipeBook implements RecipeBook {
    private static final Map<Station, String> STATION_ITEMS = Map.of(
            Station.CRAFTING_TABLE, "minecraft:crafting_table",
            Station.STONECUTTER, "minecraft:stonecutter",
            Station.SMITHING_TABLE, "minecraft:smithing_table",
            Station.FURNACE, "minecraft:furnace",
            Station.SMOKER, "minecraft:smoker",
            Station.BLAST_FURNACE, "minecraft:blast_furnace",
            Station.CAMPFIRE, "minecraft:campfire");

    private final Map<String, List<Recipe>> byOutput = new LinkedHashMap<>();

    /** The recipes the planner tests reason about, ordered like a vanilla book. */
    public static FakeRecipeBook vanillaLike() {
        Ingredient planks = Ingredient.tag(
                "minecraft:planks", "minecraft:oak_planks", "minecraft:birch_planks", "minecraft:acacia_planks");
        Ingredient ironIngot = Ingredient.of("minecraft:iron_ingot");
        Ingredient stick = Ingredient.of("minecraft:stick");
        return new FakeRecipeBook()
                .add(new Recipe(
                        "minecraft:oak_planks",
                        "minecraft:oak_planks",
                        4,
                        List.of(Ingredient.of("minecraft:oak_log")),
                        Station.INVENTORY))
                .add(new Recipe(
                        "minecraft:birch_planks",
                        "minecraft:birch_planks",
                        4,
                        List.of(Ingredient.of("minecraft:birch_log")),
                        Station.INVENTORY))
                .add(new Recipe(
                        "minecraft:acacia_planks",
                        "minecraft:acacia_planks",
                        4,
                        List.of(Ingredient.of("minecraft:acacia_log")),
                        Station.INVENTORY))
                .add(new Recipe("minecraft:stick", "minecraft:stick", 4, List.of(planks, planks), Station.INVENTORY))
                .add(new Recipe(
                        "minecraft:crafting_table",
                        "minecraft:crafting_table",
                        1,
                        List.of(planks, planks, planks, planks),
                        Station.INVENTORY))
                .add(new Recipe(
                        "minecraft:wooden_pickaxe",
                        "minecraft:wooden_pickaxe",
                        1,
                        List.of(planks, planks, planks, stick, stick),
                        Station.CRAFTING_TABLE))
                .add(new Recipe(
                        "minecraft:ladder",
                        "minecraft:ladder",
                        3,
                        Collections.nCopies(7, stick),
                        Station.CRAFTING_TABLE))
                .add(new Recipe(
                        "minecraft:iron_ingot_from_nuggets",
                        "minecraft:iron_ingot",
                        1,
                        Collections.nCopies(9, Ingredient.of("minecraft:iron_nugget")),
                        Station.INVENTORY))
                .add(new Recipe(
                        "minecraft:iron_ingot_from_block",
                        "minecraft:iron_ingot",
                        9,
                        List.of(Ingredient.of("minecraft:iron_block")),
                        Station.INVENTORY))
                .add(new Recipe(
                        "minecraft:iron_block",
                        "minecraft:iron_block",
                        1,
                        Collections.nCopies(9, ironIngot),
                        Station.INVENTORY))
                .add(new Recipe(
                        "minecraft:iron_nugget", "minecraft:iron_nugget", 9, List.of(ironIngot), Station.INVENTORY))
                .add(new Recipe(
                        "minecraft:iron_pickaxe",
                        "minecraft:iron_pickaxe",
                        1,
                        List.of(ironIngot, ironIngot, ironIngot, stick, stick),
                        Station.CRAFTING_TABLE))
                .add(new Recipe(
                        "minecraft:stonecutter",
                        "minecraft:stonecutter",
                        1,
                        List.of(
                                Ingredient.of("minecraft:stone"),
                                Ingredient.of("minecraft:stone"),
                                Ingredient.of("minecraft:stone"),
                                ironIngot),
                        Station.CRAFTING_TABLE))
                .add(new Recipe(
                        "minecraft:andesite_stairs",
                        "minecraft:andesite_stairs",
                        4,
                        Collections.nCopies(6, Ingredient.of("minecraft:andesite")),
                        Station.CRAFTING_TABLE))
                .add(new Recipe(
                        "minecraft:andesite_stairs_from_stonecutter",
                        "minecraft:andesite_stairs",
                        1,
                        List.of(Ingredient.of("minecraft:andesite")),
                        Station.STONECUTTER));
    }

    public FakeRecipeBook add(Recipe recipe) {
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
