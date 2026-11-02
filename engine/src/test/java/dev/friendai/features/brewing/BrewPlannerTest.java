package dev.friendai.features.brewing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BrewPlannerTest {
    @Test
    void strengthIsAwkwardThenBlazePowder() {
        BrewRecipe plan = BrewPlanner.plan("minecraft:strength").orElseThrow();

        assertEquals(2, plan.steps().size());
        assertEquals("minecraft:water", plan.startPotion());
        assertEquals(List.of("minecraft:nether_wart", "minecraft:blaze_powder"), plan.ingredients());
        assertEquals("minecraft:strength", plan.target());
    }

    @Test
    void startingAtAnIntermediatePotionSkipsCompletedSteps() {
        BrewRecipe plan =
                BrewPlanner.plan("minecraft:strength", "minecraft:awkward").orElseThrow();

        assertEquals(1, plan.steps().size());
        assertEquals("minecraft:awkward", plan.startPotion());
        assertEquals("minecraft:blaze_powder", plan.steps().getFirst().ingredient());
    }

    @Test
    void aPotionOffTheChainOrAnUnsupportedTargetIsRefused() {
        assertTrue(BrewPlanner.plan("minecraft:strength", "minecraft:swiftness").isEmpty());
        assertTrue(BrewPlanner.plan("minecraft:luck").isEmpty());
        assertTrue(BrewPlanner.plan(null).isEmpty());
        assertTrue(BrewPlanner.plan("minecraft:strength", null).isEmpty());
    }

    @Test
    void operationsAndFuelAreAccountedPerThreeBottles() {
        BrewRecipe plan = BrewPlanner.plan("minecraft:strength").orElseThrow();

        assertEquals(2, plan.operationCount(1));
        assertEquals(2, plan.operationCount(3));
        assertEquals(4, plan.operationCount(4));
        assertEquals(4, plan.operationCount(4)); // two steps x two operations
        assertEquals(4, plan.fuelUses(4));
        assertEquals(1, BrewRecipe.operationsPerStep(3));
        assertEquals(2, BrewRecipe.operationsPerStep(4));
        assertThrows(IllegalArgumentException.class, () -> BrewRecipe.operationsPerStep(0));
    }

    @Test
    void theCatalogIsBoundedAndEveryEntryIsReachableFromWater() {
        List<String> targets = BrewRecipes.targets();
        assertEquals(BrewRecipes.MAX_TARGETS, targets.size());
        assertEquals(targets.size(), targets.stream().distinct().count());
        for (String target : targets) {
            BrewRecipe plan = BrewPlanner.plan(target).orElseThrow();
            assertEquals(target, plan.target());
            assertEquals("minecraft:water", plan.startPotion());
            assertFalse(plan.steps().isEmpty());
        }
    }

    @Test
    void malformedCatalogDataIsRejectedBeforeItCanBeUsed() {
        assertThrows(IllegalArgumentException.class, () -> new BrewStep("", "a", "b"));
        assertThrows(IllegalArgumentException.class, () -> new BrewStep("a", "", "b"));
        assertThrows(IllegalArgumentException.class, () -> new BrewStep("a", "b", ""));
        assertThrows(IllegalArgumentException.class, () -> new BrewRecipe("", List.of(new BrewStep("a", "b", "c"))));
        assertThrows(IllegalArgumentException.class, () -> new BrewRecipe("a", List.of()));
    }
}
