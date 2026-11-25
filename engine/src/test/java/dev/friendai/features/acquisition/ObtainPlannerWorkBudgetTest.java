package dev.friendai.features.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** The planner's work budget: what one pass costs, and how a pass that runs out of it reports itself. */
class ObtainPlannerWorkBudgetTest {
    private static final String OAK_LOG = "minecraft:oak_log";
    private static final String WOODEN_PICKAXE = "minecraft:wooden_pickaxe";
    private static final String COBBLESTONE = "minecraft:cobblestone";
    private static final String GRANITE = "minecraft:granite";
    private static final String STONE = "minecraft:stone";
    private static final String FIRST = "test:first";
    private static final String SECOND = "test:second";
    private static final String THIRD = "test:third";

    private static WorldFacts world(Map<String, Integer> blocks) {
        return new WorldFacts(blocks, Map.of(), Map.of(), Map.of(), Set.of(Station.CRAFTING_TABLE.noun()), false, true);
    }

    @Test
    void an_ordinary_pass_is_not_limited_and_reports_small_counters() {
        ObtainPlan plan = ObtainPlanner.plan(
                WOODEN_PICKAXE,
                1,
                Map.of(),
                FakeRecipeBook.vanillaLike(),
                FakeSourceBook.vanillaLike(),
                world(Map.of(OAK_LOG, 6)),
                null);

        assertTrue(plan.ok(), plan.missing().toString());
        assertFalse(plan.limited());
        assertFalse(plan.steps().isEmpty());
        assertTrue(plan.work().evaluations() > 0, "the pass counted what it evaluated");
        assertTrue(plan.work().copies() > 0, "the pass counted the inventories it copied");
        assertTrue(plan.work().evaluations() < 100, plan.work().toString());
        assertTrue(plan.work().copies() < 100, plan.work().toString());
        assertEquals(1, plan.supplied(), "a complete plan supplies the whole count");
    }

    @Test
    void a_pass_counts_the_candidate_providers_it_simulated() {
        // Two recipes make the stone; both ingredients are carried, so both sources are simulated in one pass.
        FakeRecipeBook book = new FakeRecipeBook()
                .add(new Recipe(
                        "test:stone_from_cobblestone",
                        STONE,
                        1,
                        List.of(Ingredient.of(COBBLESTONE)),
                        Station.INVENTORY))
                .add(new Recipe(
                        "test:stone_from_granite", STONE, 1, List.of(Ingredient.of(GRANITE)), Station.INVENTORY));

        ObtainPlan plan = ObtainPlanner.plan(
                STONE, 1, Map.of(COBBLESTONE, 4, GRANITE, 4), book, new FakeSourceBook(), world(Map.of()), null);

        assertTrue(plan.ok(), plan.missing().toString());
        assertTrue(plan.work().providers() >= 2, "both candidate sources were simulated: " + plan.work());
        assertTrue(
                plan.work().providers() <= plan.work().copies(),
                "a provider is simulated on its own copy: " + plan.work());
    }

    @Test
    void a_partial_plan_reports_only_the_part_it_can_supply() {
        // Two oak logs are at hand and exploring is off; the third one has no source here.
        ObtainPlan plan = ObtainPlanner.plan(
                OAK_LOG,
                3,
                Map.of(),
                new FakeRecipeBook(),
                FakeSourceBook.vanillaLike(),
                world(Map.of(OAK_LOG, 2)),
                null);

        assertFalse(plan.ok());
        assertFalse(plan.limited());
        assertEquals(2, plan.supplied(), plan.steps().toString());
        assertEquals(Map.of(OAK_LOG, 1), plan.missing());
    }

    @Test
    void a_pass_that_runs_out_of_copies_is_limited_not_missing() {
        // Two ways to craft the stone: the second candidate provider is where the copy budget runs out.
        FakeRecipeBook book = new FakeRecipeBook()
                .add(new Recipe(
                        "test:stone_from_cobblestone",
                        STONE,
                        1,
                        List.of(Ingredient.of(COBBLESTONE)),
                        Station.INVENTORY))
                .add(new Recipe(
                        "test:stone_from_granite", STONE, 1, List.of(Ingredient.of(GRANITE)), Station.INVENTORY));

        ObtainPlan plan = ObtainPlanner.plan(
                STONE,
                1,
                Map.of(COBBLESTONE, 4, GRANITE, 4),
                book,
                new FakeSourceBook(),
                world(Map.of()),
                null,
                1_000,
                1);

        assertTrue(plan.limited(), "the pass stopped at its copy budget");
        assertFalse(plan.ok(), "an unfinished pass never reads as a complete plan");
        assertFalse(plan.actionable(), "an unfinished pass is not executable");
        assertEquals(1, plan.work().evaluations(), "one candidate item was evaluated");
        assertTrue(plan.work().copies() > 1, plan.work().toString());
    }

    @Test
    void a_pass_that_runs_out_of_evaluations_is_limited_not_missing() {
        // first -> second -> third, and the third is nowhere: the recursion is cut by the evaluation budget.
        FakeRecipeBook book = new FakeRecipeBook()
                .add(new Recipe("test:first", FIRST, 1, List.of(Ingredient.of(SECOND)), Station.INVENTORY))
                .add(new Recipe("test:second", SECOND, 1, List.of(Ingredient.of(THIRD)), Station.INVENTORY));

        ObtainPlan plan =
                ObtainPlanner.plan(FIRST, 1, Map.of(), book, new FakeSourceBook(), world(Map.of()), null, 1, 1_000);

        assertTrue(plan.limited(), "the pass stopped at its evaluation budget");
        assertFalse(plan.ok());
        assertTrue(
                plan.work().evaluations() > 1, "the pass kept evaluating until it exceeded the budget: " + plan.work());
    }

    @Test
    void a_completed_search_that_misses_a_material_is_not_limited() {
        ObtainPlan plan = ObtainPlanner.plan(
                THIRD, 1, Map.of(), new FakeRecipeBook(), new FakeSourceBook(), world(Map.of()), null);

        assertFalse(plan.limited(), "an empty book is a finished search");
        assertFalse(plan.ok());
        assertEquals(Map.of(THIRD, 1), plan.missing());
    }
}
