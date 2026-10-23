package dev.friedai.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CraftPlannerTest {
    private static final Set<Station> ALL_STATIONS = EnumSet.allOf(Station.class);

    private static CraftPlan plan(String target, int count, Map<String, Integer> inventory, RecipeBook book) {
        return CraftPlanner.plan(target, count, inventory, book, ALL_STATIONS);
    }

    private static long stepCount(CraftPlan plan, String output) {
        return plan.steps().stream().filter(step -> step.recipe().output().equals(output)).count();
    }

    private static CraftStep step(CraftPlan plan, String output) {
        assertEquals(1, stepCount(plan, output), "expected one step for " + output + " in " + plan.steps());
        return plan.steps().stream().filter(s -> s.recipe().output().equals(output)).findFirst().orElseThrow();
    }

    @Test
    void planksFromLog() {
        CraftPlan plan = plan("minecraft:oak_planks", 4, Map.of("minecraft:oak_log", 1), FakeRecipeBook.vanillaLike());
        assertTrue(plan.ok());
        assertEquals(1, plan.steps().size());
        CraftStep step = plan.steps().get(0);
        assertEquals("minecraft:oak_planks", step.recipe().output());
        assertEquals(1, step.times());
    }

    @Test
    void sticksFromLogs() {
        CraftPlan plan = plan("minecraft:stick", 4, Map.of("minecraft:oak_log", 1), FakeRecipeBook.vanillaLike());
        assertTrue(plan.ok());
        assertEquals(2, plan.steps().size());
        assertEquals("minecraft:oak_planks", plan.steps().get(0).recipe().output());
        assertEquals("minecraft:stick", plan.steps().get(1).recipe().output());
        assertEquals(1, plan.steps().get(0).times());
        assertEquals(1, plan.steps().get(1).times());
    }

    @Test
    void countRoundsUp() {
        CraftPlan plan = plan("minecraft:stick", 5, Map.of("minecraft:oak_planks", 4), FakeRecipeBook.vanillaLike());
        assertTrue(plan.ok());
        assertEquals(1, plan.steps().size());
        assertEquals(2, plan.steps().get(0).times());
    }

    @Test
    void tagAlternativeUsesWhatIsCarried() {
        CraftPlan plan = plan("minecraft:stick", 4,
                Map.of("minecraft:birch_planks", 2, "minecraft:oak_log", 1), FakeRecipeBook.vanillaLike());
        assertTrue(plan.ok());
        assertEquals(1, plan.steps().size());
        assertEquals("minecraft:stick", plan.steps().get(0).recipe().output());
        assertEquals(1, plan.steps().get(0).times());
        assertEquals(0, stepCount(plan, "minecraft:oak_planks"));
    }

    @Test
    void leftoversFeedLaterSteps() {
        CraftPlan plan = plan("minecraft:wooden_pickaxe", 1, Map.of("minecraft:oak_log", 3),
                FakeRecipeBook.vanillaLike());
        assertTrue(plan.ok());
        // 3 planks for the head + 4 planks for two sticks = 7 planks: two crafts of four, merged into one step.
        assertEquals(2, step(plan, "minecraft:oak_planks").times());
        assertEquals(3, plan.steps().size());
        assertEquals("minecraft:wooden_pickaxe", plan.steps().get(2).recipe().output());
    }

    @Test
    void cycleWithNuggets() {
        CraftPlan plan = plan("minecraft:iron_ingot", 1, Map.of("minecraft:iron_nugget", 9),
                FakeRecipeBook.vanillaLike());
        assertTrue(plan.ok());
        assertEquals(1, plan.steps().size());
        assertEquals("minecraft:iron_ingot_from_nuggets", plan.steps().get(0).recipe().id());
        assertEquals(1, plan.steps().get(0).times());
    }

    @Test
    void cycleWithBlock() {
        CraftPlan plan = plan("minecraft:iron_ingot", 1, Map.of("minecraft:iron_block", 1),
                FakeRecipeBook.vanillaLike());
        assertTrue(plan.ok());
        assertEquals(1, plan.steps().size());
        assertEquals("minecraft:iron_ingot_from_block", plan.steps().get(0).recipe().id());
        assertEquals(1, plan.steps().get(0).times());
    }

    @Test
    void cycleWithNothing() {
        CraftPlan plan = plan("minecraft:iron_ingot", 1, Map.of(), FakeRecipeBook.vanillaLike());
        assertFalse(plan.ok());
        assertEquals(Map.of("minecraft:iron_ingot", 1), plan.missing());
    }

    @Test
    void missingItemItselfWhenNoProgress() {
        CraftPlan plan = plan("minecraft:iron_pickaxe", 1,
                Map.of("minecraft:stick", 2, "minecraft:iron_ingot", 1), FakeRecipeBook.vanillaLike());
        assertFalse(plan.ok());
        assertEquals(Map.of("minecraft:iron_ingot", 2), plan.missing());
    }

    @Test
    void missingLabelsUseTags() {
        CraftPlan plan = plan("minecraft:wooden_pickaxe", 1, Map.of(), FakeRecipeBook.vanillaLike());
        assertFalse(plan.ok());
        assertEquals(Map.of("#minecraft:planks", 3, "minecraft:stick", 2), plan.missing());
    }

    @Test
    void missingDependencyReportedItself() {
        // The crafting table cannot start, so a dependency goal names the table, not its planks.
        FakeRecipeBook book = FakeRecipeBook.vanillaLike()
                .add(new Recipe("test:bench_part", "test:bench_part", 1,
                        List.of(Ingredient.of("minecraft:crafting_table")), Station.INVENTORY));
        CraftPlan plan = plan("test:bench_part", 1, Map.of(), book);
        assertFalse(plan.ok());
        assertEquals(Map.of("minecraft:crafting_table", 1), plan.missing());
    }

    @Test
    void fallsBackToSecondRecipe() {
        Ingredient planks =
                Ingredient.tag("minecraft:planks", "minecraft:oak_planks", "minecraft:birch_planks");
        FakeRecipeBook book = new FakeRecipeBook()
                .add(new Recipe("test:sticks_bamboo", "minecraft:stick", 4,
                        List.of(Ingredient.of("minecraft:bamboo"), Ingredient.of("minecraft:bamboo")),
                        Station.INVENTORY))
                .add(new Recipe("test:sticks_planks", "minecraft:stick", 4,
                        List.of(planks, planks), Station.INVENTORY));
        CraftPlan plan = plan("minecraft:stick", 4, Map.of("minecraft:oak_planks", 2), book);
        assertTrue(plan.ok());
        assertEquals(1, plan.steps().size());
        assertEquals("test:sticks_planks", plan.steps().get(0).recipe().id());
        assertEquals(1, plan.steps().get(0).times());
    }

    @Test
    void depthLimit() {
        FakeRecipeBook book = new FakeRecipeBook();
        for (int i = 1; i <= 6; i++) {
            book.add(new Recipe("test:chain_" + i, "test:a" + i, 1,
                    List.of(Ingredient.of("test:a" + (i + 1))), Station.INVENTORY));
        }
        CraftPlan plan = plan("test:a1", 1, Map.of("test:a7", 1), book);
        assertFalse(plan.ok());
        assertFalse(plan.missing().isEmpty());
    }

    @Test
    void mergesRepeatedRecipe() {
        FakeRecipeBook book = FakeRecipeBook.vanillaLike()
                .add(new Recipe("test:a", "test:a", 1,
                        List.of(Ingredient.of("minecraft:oak_planks"), Ingredient.of("minecraft:oak_planks"),
                                Ingredient.of("minecraft:oak_planks"), Ingredient.of("minecraft:oak_planks")),
                        Station.INVENTORY))
                .add(new Recipe("test:b", "test:b", 1,
                        List.of(Ingredient.of("minecraft:oak_planks"), Ingredient.of("minecraft:oak_planks"),
                                Ingredient.of("minecraft:oak_planks"), Ingredient.of("minecraft:oak_planks")),
                        Station.INVENTORY))
                .add(new Recipe("test:widget", "test:widget", 1,
                        List.of(Ingredient.of("test:a"), Ingredient.of("test:b")), Station.INVENTORY));
        CraftPlan plan = plan("test:widget", 1, Map.of("minecraft:oak_log", 2), book);
        assertTrue(plan.ok());
        assertEquals(4, plan.steps().size());
        CraftStep planks = step(plan, "minecraft:oak_planks");
        assertEquals(2, planks.times());
        assertEquals("minecraft:oak_planks", plan.steps().get(0).recipe().output());
    }
}
