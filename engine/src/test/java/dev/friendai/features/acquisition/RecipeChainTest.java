package dev.friendai.features.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.crafting.FakeRecipeBook;
import dev.friendai.features.crafting.Ingredient;
import dev.friendai.features.crafting.Recipe;
import dev.friendai.features.crafting.Station;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The recipe walk reports what it covered, so a cut branch is not mistaken for a missing source. */
class RecipeChainTest {
    private static final String COAL_ORE = "minecraft:coal_ore";
    private static final String IRON_ORE = "minecraft:iron_ore";

    /** A direct source and a source five recipe hops deeper. */
    private static FakeRecipeBook deepBook() {
        return new FakeRecipeBook()
                .add(recipe("test:deep_target", "minecraft:coal"))
                .add(recipe("test:deep_target", "test:deep_1"))
                .add(recipe("test:deep_1", "test:deep_2"))
                .add(recipe("test:deep_2", "test:deep_3"))
                .add(recipe("test:deep_3", "test:deep_4"))
                .add(recipe("test:deep_4", "test:deep_5"))
                .add(recipe("test:deep_5", "minecraft:raw_iron"));
    }

    private static Recipe recipe(String output, String ingredient) {
        return new Recipe(
                "test:" + output + "_recipe", output, 1, List.of(Ingredient.of(ingredient)), Station.INVENTORY);
    }

    private static FakeSourceBook sources() {
        return FakeSourceBook.vanillaLike();
    }

    @Test
    void aBranchCutAtTheDepthLimitIsReportedAsPartialWithTheSourcesFoundSoFar() {
        RecipeChain.Result bounded = RecipeChain.resolve("test:deep_target", deepBook(), sources());
        assertEquals(RecipeChain.Coverage.PARTIAL, bounded.coverage());
        assertEquals(Set.of(COAL_ORE), bounded.blockIds());
        assertTrue(bounded.executable(), "the shallow source is still usable");
        assertTrue(bounded.limit().contains("depth limit"), bounded.limit());

        RecipeChain.Result wider = RecipeChain.resolve(
                "test:deep_target",
                deepBook(),
                sources(),
                new RecipeChain.Policy(8, RecipeChain.Policy.DEFAULT.maxItems()));
        assertEquals(RecipeChain.Coverage.COMPLETE, wider.coverage(), wider.limit());
        assertEquals(Set.of(COAL_ORE, IRON_ORE), wider.blockIds());
        assertTrue(wider.limit().isEmpty(), wider.limit());
    }

    @Test
    void pumpingTheWorkBudgetReportsTheBlocksFoundSoFar() {
        RecipeChain.Result result =
                RecipeChain.resolve("test:deep_target", deepBook(), sources(), new RecipeChain.Policy(5, 3));
        assertEquals(RecipeChain.Coverage.PARTIAL, result.coverage());
        assertTrue(result.blockIds().contains(COAL_ORE), result.blockIds().toString());
        assertTrue(result.limit().contains("work budget of 3 items"), result.limit());
    }

    @Test
    void aCyclicRecipeTerminatesAndIsNotACut() {
        FakeRecipeBook cyclic = new FakeRecipeBook()
                .add(recipe("test:loop", "test:loop_a"))
                .add(recipe("test:loop_a", "test:loop_b"))
                .add(recipe("test:loop_b", "test:loop_a"))
                .add(recipe("test:loop_b", "minecraft:coal"));

        RecipeChain.Result result = RecipeChain.resolve("test:loop", cyclic, sources());
        assertEquals(RecipeChain.Coverage.COMPLETE, result.coverage(), result.limit());
        assertEquals(Set.of(COAL_ORE), result.blockIds());
    }

    @Test
    void anItemWithNoRecipeAndNoSourceIsUnsupported() {
        RecipeChain.Result result = RecipeChain.resolve("test:nothing", new FakeRecipeBook(), sources());
        assertEquals(RecipeChain.Coverage.UNSUPPORTED, result.coverage());
        assertFalse(result.executable());
        assertTrue(result.blockIds().isEmpty());
    }

    @Test
    void aCraftedBlockIsNotItsOwnSourceAndAHarvestCropIsOne() {
        FakeRecipeBook book = new FakeRecipeBook().add(recipe("minecraft:oak_planks", "minecraft:oak_log"));
        FakeSourceBook withPlanks = sources()
                .add("minecraft:oak_planks", new Source.Mine("minecraft:oak_planks", ToolNeed.HAND, 1, true, 0));

        assertEquals(
                Set.of("minecraft:oak_log"),
                RecipeChain.resolve("minecraft:oak_planks", book, withPlanks).blockIds());
        assertEquals(
                Set.of("minecraft:wheat"),
                RecipeChain.resolve("minecraft:wheat", new FakeRecipeBook(), sources())
                        .blockIds());
    }
}
