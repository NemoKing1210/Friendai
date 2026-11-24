package dev.friendai.features.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.QueuedStep;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanIngredientsTest {
    private static final Ingredient WHEAT = Ingredient.of("minecraft:wheat");
    private static final Ingredient BEEF = Ingredient.of("minecraft:beef");

    @Test
    void aBreadOrderCommitsTheWheatItWillBakeFrom() {
        FakeRecipeBook book = new FakeRecipeBook()
                .add(new Recipe(
                        "minecraft:bread", "minecraft:bread", 1, List.of(WHEAT, WHEAT, WHEAT), Station.CRAFTING_TABLE));

        Map<String, Integer> committed = PlanIngredients.committed(
                List.of(step("craft_item", "{\"item\":\"minecraft:bread\",\"count\":2}")),
                book,
                Set.of("minecraft:wheat", "minecraft:bread"));

        assertEquals(Map.of("minecraft:wheat", 6), committed, "two loaves commit six wheat");
    }

    @Test
    void aCookingOrderCommitsItsRawInput() {
        FakeRecipeBook book = new FakeRecipeBook()
                .add(new Recipe(
                        "minecraft:cooked_beef", "minecraft:cooked_beef", 1, List.of(BEEF), Station.FURNACE, 200));

        Map<String, Integer> committed = PlanIngredients.committed(
                List.of(step("smelt_items", "{\"item\":\"minecraft:cooked_beef\",\"count\":3}")),
                book,
                Set.of("minecraft:beef"));

        assertEquals(Map.of("minecraft:beef", 3), committed);
    }

    @Test
    void aRecipeWhoseSlotsAreNotCarriedReservesNothing() {
        FakeRecipeBook book = new FakeRecipeBook()
                .add(new Recipe(
                        "minecraft:bread", "minecraft:bread", 1, List.of(WHEAT, WHEAT, WHEAT), Station.CRAFTING_TABLE));

        Map<String, Integer> committed = PlanIngredients.committed(
                List.of(step("craft_item", "{\"item\":\"minecraft:bread\",\"count\":1}")),
                book,
                Set.of("minecraft:stone"));

        assertTrue(committed.isEmpty(), "an impossible plan must not lock the bag: " + committed);
    }

    @Test
    void aMalformedStepAndAnUnnamedToolContributeNothing() {
        FakeRecipeBook book = new FakeRecipeBook()
                .add(new Recipe(
                        "minecraft:bread", "minecraft:bread", 1, List.of(WHEAT, WHEAT, WHEAT), Station.CRAFTING_TABLE));

        Map<String, Integer> committed = PlanIngredients.committed(
                List.of(
                        step("craft_item", "{\"count\":2}"),
                        step("wait", "{}"),
                        step("obtain_item", "{\"item\":\"minecraft:bread\",\"count\":0}")),
                book,
                Set.of("minecraft:wheat"));

        assertTrue(committed.isEmpty(), "a broken step reserves nothing: " + committed);
    }

    @Test
    void aBatchStepCommitsEachTargetsIngredients() {
        FakeRecipeBook book = new FakeRecipeBook()
                .add(new Recipe(
                        "minecraft:bread", "minecraft:bread", 1, List.of(WHEAT, WHEAT, WHEAT), Station.CRAFTING_TABLE))
                .add(new Recipe(
                        "minecraft:cooked_beef", "minecraft:cooked_beef", 1, List.of(BEEF), Station.FURNACE, 200));

        Map<String, Integer> committed = PlanIngredients.committed(
                List.of(step(
                        "obtain_items",
                        "{\"items\":[{\"item\":\"minecraft:bread\",\"count\":1},"
                                + "{\"item\":\"minecraft:cooked_beef\",\"count\":2}]}")),
                book,
                Set.of("minecraft:wheat", "minecraft:beef"));

        assertEquals(Map.of("minecraft:wheat", 3, "minecraft:beef", 2), committed);
    }

    @Test
    void aRecipeLargerThanTheOutputCountCommitsPerCraft() {
        FakeRecipeBook book = new FakeRecipeBook()
                .add(new Recipe(
                        "minecraft:torch",
                        "minecraft:torch",
                        4,
                        List.of(Ingredient.of("minecraft:coal"), Ingredient.of("minecraft:stick")),
                        Station.CRAFTING_TABLE));

        Map<String, Integer> committed = PlanIngredients.committed(
                List.of(step("craft_item", "{\"item\":\"minecraft:torch\",\"count\":5}")),
                book,
                Set.of("minecraft:coal", "minecraft:stick"));

        assertEquals(
                Map.of("minecraft:coal", 2, "minecraft:stick", 2), committed, "five torches are two crafts of four");
    }

    private static QueuedStep step(String tool, String args) {
        return new QueuedStep(
                tool,
                com.google.gson.JsonParser.parseString(args).getAsJsonObject(),
                null,
                List.of(),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                List.of());
    }
}
