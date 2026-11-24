package dev.friendai.features.acquisition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.crafting.FakeRecipeBook;
import dev.friendai.features.crafting.Ingredient;
import dev.friendai.features.crafting.Recipe;
import dev.friendai.features.crafting.Station;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A local-only comparison asks whether an ingredient is obtainable where the companion stands. The tool and station
 * it needs must be planned under the same policy, or a chain that actually needs a trip is dressed up as local.
 */
class ObtainPlannerLocalComparisonTest {
    private static final String COBBLESTONE = "minecraft:cobblestone";
    private static final String RAW_IRON = "minecraft:raw_iron";
    private static final String IRON_ORE = "minecraft:iron_ore";
    private static final String GRAVEL = "minecraft:gravel";
    private static final String PLANKS = "minecraft:oak_planks";
    private static final String LATH = "test:lathe";
    private static final String TARGET = "test:choice";

    /**
     * A target with two possible ingredients. {@code minecraft:raw_iron} is cheaper in work but needs a stone
     * pickaxe whose handle is only obtainable by exploring for logs; {@code minecraft:gravel} is a slower but
     * verified local source.
     */
    private static FakeRecipeBook toolBook() {
        return FakeRecipeBook.vanillaLike()
                .add(new Recipe(
                        "test:stone_pickaxe",
                        "minecraft:stone_pickaxe",
                        1,
                        List.of(
                                Ingredient.of(COBBLESTONE),
                                Ingredient.of(COBBLESTONE),
                                Ingredient.of(COBBLESTONE),
                                Ingredient.of("minecraft:stick"),
                                Ingredient.of("minecraft:stick")),
                        Station.CRAFTING_TABLE))
                .add(new Recipe("test:choice", TARGET, 1, List.of(choice(RAW_IRON, GRAVEL)), Station.INVENTORY));
    }

    /**
     * The same shape, but the cheap ingredient is a craft at a table the companion does not have: the table is only
     * obtainable by exploring for logs, while the gravel stays local.
     */
    private static FakeRecipeBook stationBook() {
        return FakeRecipeBook.vanillaLike()
                .add(new Recipe("test:lathe", LATH, 1, List.of(Ingredient.of(PLANKS)), Station.CRAFTING_TABLE))
                .add(new Recipe("test:choice", TARGET, 1, List.of(choice(LATH, GRAVEL)), Station.INVENTORY));
    }

    /** One slot with two item alternatives, in the order the planner should compare them. */
    private static Ingredient choice(String first, String second) {
        return new Ingredient(new java.util.LinkedHashSet<>(List.of(first, second)), first);
    }

    private static FakeSourceBook sources() {
        return FakeSourceBook.vanillaLike().add(GRAVEL, new Source.Mine(GRAVEL, ToolNeed.HAND, 0.1, true, 0));
    }

    private static WorldFacts world(Map<String, Integer> blocks, Set<String> stations) {
        return new WorldFacts(blocks, Map.of(), Map.of(), Map.of(), stations, true, true);
    }

    private static boolean explores(ObtainPlan plan) {
        return plan.steps().stream().anyMatch(ObtainStep.Explore.class::isInstance);
    }

    private static boolean mines(ObtainPlan plan, String block) {
        return plan.steps().stream()
                .filter(ObtainStep.Mine.class::isInstance)
                .map(ObtainStep.Mine.class::cast)
                .anyMatch(mine -> mine.block().equals(block));
    }

    @Test
    void aLocalComparisonDoesNotBootstrapAToolThroughExploration() {
        ObtainPlan plan = ObtainPlanner.plan(
                TARGET,
                1,
                Map.of(COBBLESTONE, 3),
                toolBook(),
                sources(),
                world(Map.of(IRON_ORE, 32, GRAVEL, 64), Set.of(Station.CRAFTING_TABLE.noun())));

        assertTrue(plan.ok(), plan.missing().toString());
        assertFalse(
                explores(plan),
                "a tool chain that needs a trip must not make the ingredient look local: " + plan.steps());
        assertTrue(mines(plan, GRAVEL), "the verified local source wins: " + plan.steps());
    }

    @Test
    void aLocalComparisonDoesNotCraftAStationThroughExploration() {
        ObtainPlan plan = ObtainPlanner.plan(
                TARGET, 1, Map.of(PLANKS, 1), stationBook(), sources(), world(Map.of(GRAVEL, 64), Set.of()));

        assertTrue(plan.ok(), plan.missing().toString());
        assertFalse(
                explores(plan), "a station that needs a trip must not make the ingredient look local: " + plan.steps());
        assertTrue(mines(plan, GRAVEL), "the verified local source wins: " + plan.steps());
    }
}
