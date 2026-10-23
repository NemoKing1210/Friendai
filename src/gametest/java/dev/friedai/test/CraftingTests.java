package dev.friedai.test;

import dev.friedai.crafting.Ingredient;
import dev.friedai.crafting.Recipe;
import dev.friedai.crafting.Station;
import dev.friedai.task.crafting.RecipeIndex;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class CraftingTests {
    private static List<Recipe> producing(GameTestHelper h, String item) {
        return RecipeIndex.of(h.getLevel()).producing(item);
    }

    private static Recipe firstAt(GameTestHelper h, String item, Station station) {
        return producing(h, item).stream()
                .filter(recipe -> recipe.station() == station)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + station + " recipe for " + item));
    }

    @GameTest
    public void indexHasInventoryAndTableRecipes(GameTestHelper h) {
        Recipe planks = firstAt(h, "minecraft:oak_planks", Station.INVENTORY);
        h.assertValueEqual("minecraft:oak_planks", planks.output(), "oak planks output");
        h.assertValueEqual(4, planks.outputCount(), "oak planks output count");
        h.assertTrue(
                planks.ingredients().stream()
                        .anyMatch(ingredient -> ingredient.items().contains("minecraft:oak_log")),
                "oak planks ingredient should accept oak logs: " + planks.ingredients());

        h.assertTrue(
                producing(h, "minecraft:iron_pickaxe").stream()
                        .anyMatch(recipe -> recipe.station() == Station.CRAFTING_TABLE),
                "iron pickaxe needs a crafting table recipe");

        firstAt(h, "minecraft:crafting_table", Station.INVENTORY);
        h.succeed();
    }

    @GameTest
    public void indexLabelsTags(GameTestHelper h) {
        List<Ingredient> ingredients = firstAt(h, "minecraft:stick", Station.INVENTORY).ingredients();
        h.assertTrue(
                ingredients.stream().anyMatch(ingredient -> "#minecraft:planks".equals(ingredient.label())),
                "stick recipe should be labelled with #minecraft:planks: " + ingredients);
        h.succeed();
    }

    @GameTest
    public void indexHasStonecutterAndSmithing(GameTestHelper h) {
        List<Recipe> stairs = producing(h, "minecraft:andesite_stairs");
        h.assertTrue(
                stairs.stream().filter(recipe -> recipe.station() == Station.CRAFTING_TABLE).count() == 1,
                "andesite stairs crafting recipes: " + stairs);
        h.assertTrue(
                stairs.stream().filter(recipe -> recipe.station() == Station.STONECUTTER).count() == 1,
                "andesite stairs stonecutter recipes: " + stairs);

        Recipe sword = firstAt(h, "minecraft:netherite_sword", Station.SMITHING_TABLE);
        h.assertValueEqual(3, sword.ingredients().size(), "netherite sword smithing ingredients: " + sword);
        h.succeed();
    }

    @GameTest
    public void indexSkipsSpecialAndTrims(GameTestHelper h) {
        List<Recipe> rockets = producing(h, "minecraft:firework_rocket");
        h.assertTrue(
                rockets.stream().noneMatch(recipe -> "minecraft:firework_rocket".equals(recipe.id())),
                "the special firework rocket recipe must be skipped: " + rockets);
        h.assertTrue(
                producing(h, "minecraft:firework_star").isEmpty(),
                "special firework star recipes must be skipped: " + producing(h, "minecraft:firework_star"));
        h.assertTrue(
                producing(h, "minecraft:diamond_chestplate").stream()
                        .noneMatch(recipe -> recipe.station() == Station.SMITHING_TABLE),
                "smithing trims must be skipped: " + producing(h, "minecraft:diamond_chestplate"));
        h.succeed();
    }

    @GameTest
    public void indexIsCached(GameTestHelper h) {
        h.assertTrue(
                RecipeIndex.of(h.getLevel()) == RecipeIndex.of(h.getLevel()),
                "RecipeIndex should be cached per RecipeManager");
        h.succeed();
    }
}
