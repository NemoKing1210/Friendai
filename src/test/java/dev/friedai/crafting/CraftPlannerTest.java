package dev.friedai.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CraftPlannerTest {
    private static final Set<Station> ALL_STATIONS = EnumSet.allOf(Station.class);

    private static CraftPlan plan(String target, int count, Map<String, Integer> inventory, RecipeBook book) {
        return plan(target, count, inventory, book, ALL_STATIONS);
    }

    private static CraftPlan plan(String target, int count, Map<String, Integer> inventory, RecipeBook book,
            Set<Station> stations) {
        return CraftPlanner.plan(target, count, inventory, book, stations);
    }

    private static int indexOf(CraftPlan plan, String output) {
        List<CraftStep> steps = plan.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).recipe().output().equals(output)) {
                return i;
            }
        }
        return -1;
    }

    private static long stepCount(CraftPlan plan, String output) {
        return plan.steps().stream().filter(step -> step.recipe().output().equals(output)).count();
    }

    private static CraftStep step(CraftPlan plan, String output) {
        assertEquals(1, stepCount(plan, output), "expected one step for " + output + " in " + plan.steps());
        return plan.steps().stream().filter(s -> s.recipe().output().equals(output)).findFirst().orElseThrow();
    }

    /**
     * Replays a plan the way the executor does: steps in order, each craft takes the first accepted item per
     * slot from a greedy inventory, then adds the result. A station that is not nearby is assumed carried (the
     * executor places it and keeps it placed until the plan ends, and the planner reserves it, so no craft
     * consumes it). Returns null when the whole plan runs, otherwise the first missing item.
     */
    private static String replay(CraftPlan plan, RecipeBook book, Map<String, Integer> inventory,
            Set<Station> stationsNearby) {
        Map<String, Integer> items = new HashMap<>(inventory);
        for (CraftStep step : plan.steps()) {
            Station station = step.recipe().station();
            if (station != Station.INVENTORY && !stationsNearby.contains(station)) {
                String stationItem = book.stationItem(station);
                if (stationItem == null || items.getOrDefault(stationItem, 0) < 1) {
                    return "station " + station + " is neither nearby nor carried";
                }
            }
            for (int craft = 0; craft < step.times(); craft++) {
                for (Ingredient ingredient : step.recipe().ingredients()) {
                    String picked = null;
                    for (String id : ingredient.items()) {
                        if (items.getOrDefault(id, 0) > 0) {
                            picked = id;
                            break;
                        }
                    }
                    if (picked == null) {
                        return step.recipe().output() + " craft " + (craft + 1) + " lacks " + ingredient.label();
                    }
                    items.merge(picked, -1, Integer::sum);
                }
                items.merge(step.recipe().output(), step.recipe().outputCount(), Integer::sum);
            }
        }
        return null;
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
    void mergedStepsRunInDependencyOrder() {
        // The ladder has seven stick slots: the first stick craft uses the two carried planks, the second needs
        // planks crafted from the log. Merging the crafts into one step must not run it before that planks step.
        RecipeBook book = FakeRecipeBook.vanillaLike();
        Map<String, Integer> inventory = Map.of("minecraft:oak_planks", 2, "minecraft:oak_log", 1);
        Set<Station> stations = EnumSet.of(Station.CRAFTING_TABLE);
        CraftPlan plan = plan("minecraft:ladder", 1, inventory, book, stations);
        assertTrue(plan.ok(), () -> "missing " + plan.missing());
        assertEquals(2, step(plan, "minecraft:stick").times());
        assertEquals(1, step(plan, "minecraft:oak_planks").times());
        assertTrue(indexOf(plan, "minecraft:oak_planks") < indexOf(plan, "minecraft:stick"),
                "planks must be crafted before the sticks: " + plan.steps());
        assertNull(replay(plan, book, inventory, stations), "the plan must run in order: " + plan.steps());
        assertEquals("minecraft:ladder", plan.steps().get(plan.steps().size() - 1).recipe().output());
    }

    @Test
    void replayRejectsDependencyInversion() {
        // The pre-fix order of the ladder plan: the merged stick step before the planks it needs for craft 2.
        FakeRecipeBook book = FakeRecipeBook.vanillaLike();
        Recipe stick = book.producing("minecraft:stick").get(0);
        Recipe planks = book.producing("minecraft:oak_planks").get(0);
        Recipe ladder = book.producing("minecraft:ladder").get(0);
        CraftPlan inverted = new CraftPlan(
                List.of(new CraftStep(stick, 2), new CraftStep(planks, 1), new CraftStep(ladder, 1)),
                Map.of(), Set.of(Station.CRAFTING_TABLE), Set.of());
        assertEquals(
                "minecraft:stick craft 2 lacks #minecraft:planks",
                replay(inverted, book, Map.of("minecraft:oak_planks", 2, "minecraft:oak_log", 1),
                        EnumSet.of(Station.CRAFTING_TABLE)));
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

    @Test
    void tableCraftedWhenMissing() {
        CraftPlan plan = plan("minecraft:wooden_pickaxe", 1, Map.of("minecraft:oak_log", 3),
                FakeRecipeBook.vanillaLike(), EnumSet.noneOf(Station.class));
        assertTrue(plan.ok());
        assertEquals(Set.of(Station.CRAFTING_TABLE), plan.stationsToCraft());
        assertEquals(1, step(plan, "minecraft:crafting_table").times());
        assertTrue(indexOf(plan, "minecraft:crafting_table") < indexOf(plan, "minecraft:wooden_pickaxe"));
    }

    @Test
    void tableNearbyNotCrafted() {
        CraftPlan plan = plan("minecraft:wooden_pickaxe", 1, Map.of("minecraft:oak_log", 3),
                FakeRecipeBook.vanillaLike(), EnumSet.of(Station.CRAFTING_TABLE));
        assertTrue(plan.ok());
        assertEquals(0, stepCount(plan, "minecraft:crafting_table"));
        assertTrue(plan.stationsToCraft().isEmpty());
        assertEquals(Set.of(Station.CRAFTING_TABLE), plan.stationsUsed());
        assertEquals(2, step(plan, "minecraft:oak_planks").times());
    }

    @Test
    void carriedTableIsReserved() {
        CraftPlan plan = plan("minecraft:wooden_pickaxe", 1,
                Map.of("minecraft:oak_log", 2, "minecraft:crafting_table", 1), FakeRecipeBook.vanillaLike(),
                EnumSet.noneOf(Station.class));
        assertTrue(plan.ok());
        assertEquals(0, stepCount(plan, "minecraft:crafting_table"));
        assertTrue(plan.stationsToCraft().isEmpty());
        // Five planks are needed, which two logs (eight planks) cover.
        assertEquals(2, step(plan, "minecraft:oak_planks").times());
        assertEquals("minecraft:wooden_pickaxe", plan.steps().get(plan.steps().size() - 1).recipe().output());
    }

    @Test
    void missingIncludesTable() {
        CraftPlan plan = plan("minecraft:wooden_pickaxe", 1, Map.of(), FakeRecipeBook.vanillaLike(),
                EnumSet.noneOf(Station.class));
        assertFalse(plan.ok());
        assertEquals(Map.of("#minecraft:planks", 3, "minecraft:stick", 2, "minecraft:crafting_table", 1),
                plan.missing());
    }

    @Test
    void stonecutterPreferredWhenNearby() {
        CraftPlan plan = plan("minecraft:andesite_stairs", 4, Map.of("minecraft:andesite", 4),
                FakeRecipeBook.vanillaLike(), EnumSet.of(Station.CRAFTING_TABLE, Station.STONECUTTER));
        assertTrue(plan.ok());
        CraftStep stairs = step(plan, "minecraft:andesite_stairs");
        assertEquals("minecraft:andesite_stairs_from_stonecutter", stairs.recipe().id());
        assertEquals(Station.STONECUTTER, stairs.recipe().station());
        assertEquals(4, stairs.times());
        assertEquals(Set.of(Station.STONECUTTER), plan.stationsUsed());
        assertTrue(plan.stationsToCraft().isEmpty());
    }

    @Test
    void tableRecipeWhenNoStonecutter() {
        CraftPlan plan = plan("minecraft:andesite_stairs", 4, Map.of("minecraft:andesite", 6),
                FakeRecipeBook.vanillaLike(), EnumSet.of(Station.CRAFTING_TABLE));
        assertTrue(plan.ok());
        CraftStep stairs = step(plan, "minecraft:andesite_stairs");
        assertEquals("minecraft:andesite_stairs", stairs.recipe().id());
        assertEquals(Station.CRAFTING_TABLE, stairs.recipe().station());
        assertEquals(1, stairs.times());
        assertEquals(0, stepCount(plan, "minecraft:stonecutter"));
    }

    @Test
    void cookingOnlyItemIsMissing() {
        FakeRecipeBook book = FakeRecipeBook.vanillaLike()
                .add(new Recipe("minecraft:iron_ingot_from_smelting_raw_iron", "minecraft:iron_ingot", 1,
                        List.of(Ingredient.of("minecraft:raw_iron")), Station.FURNACE, 200));
        CraftPlan plan = plan("minecraft:iron_ingot", 1, Map.of("minecraft:raw_iron", 3), book);
        assertFalse(plan.ok());
        assertEquals(Map.of("minecraft:iron_ingot", 1), plan.missing());
    }

    @Test
    void craftingRecipeNextToCooking() {
        FakeRecipeBook book = FakeRecipeBook.vanillaLike()
                .add(new Recipe("minecraft:iron_ingot_from_smelting_raw_iron", "minecraft:iron_ingot", 1,
                        List.of(Ingredient.of("minecraft:raw_iron")), Station.FURNACE, 200));
        CraftPlan plan = plan("minecraft:iron_ingot", 1, Map.of("minecraft:iron_nugget", 9), book);
        assertTrue(plan.ok());
        assertEquals(1, plan.steps().size());
        assertEquals("minecraft:iron_ingot_from_nuggets", plan.steps().get(0).recipe().id());
    }

    @Test
    void inventoryRecipesNeedNoStation() {
        CraftPlan plan = plan("minecraft:oak_planks", 4, Map.of("minecraft:oak_log", 1),
                FakeRecipeBook.vanillaLike(), EnumSet.noneOf(Station.class));
        assertTrue(plan.ok());
        assertEquals(1, plan.steps().size());
        assertEquals(Station.INVENTORY, plan.steps().get(0).recipe().station());
        assertTrue(plan.stationsUsed().isEmpty());
        assertTrue(plan.stationsToCraft().isEmpty());
    }
}
