package dev.friendai.farming;

import static dev.friendai.farming.FarmFixtures.WHEAT_SEEDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FarmPlannerTest {
    private static FarmPlan plan(FarmSnapshot snapshot) {
        return FarmPlanner.plan(snapshot, FarmGoal.defaults(), FarmFixtures.book());
    }

    @Test
    void harvestsRipeCellsWithReplantPairs() {
        FarmSnapshot snapshot = FarmFixtures.field(Map.of(WHEAT_SEEDS, 3), "RRR");
        List<FieldCell> c = snapshot.cells();
        assertEquals(List.of(
                new FarmStep.Harvest(c.get(0)), new FarmStep.Replant(c.get(0), WHEAT_SEEDS),
                new FarmStep.Harvest(c.get(1)), new FarmStep.Replant(c.get(1), WHEAT_SEEDS),
                new FarmStep.Harvest(c.get(2)), new FarmStep.Replant(c.get(2), WHEAT_SEEDS)),
                plan(snapshot).steps());
    }

    @Test
    void fruitIsHarvestedButNotReplanted() {
        FarmSnapshot snapshot = FarmFixtures.field(Map.of(), "F");
        assertEquals(List.of(new FarmStep.Harvest(snapshot.cells().get(0))), plan(snapshot).steps());
    }

    @Test
    void serpentineOrder() {
        FarmSnapshot snapshot = FarmFixtures.field(Map.of(WHEAT_SEEDS, 6), "RR", "RR", "RR");
        List<int[]> visited = new ArrayList<>();
        for (FarmStep step : plan(snapshot).steps()) {
            if (step instanceof FarmStep.Harvest harvest) {
                visited.add(new int[] {harvest.cell().dx(), harvest.cell().dz()});
            }
        }
        assertEquals(List.of("0,0", "1,0", "1,1", "0,1", "0,2", "1,2"),
                visited.stream().map(p -> p[0] + "," + p[1]).toList());
    }

    @Test
    void emptyAndBlockedFieldGivesEmptyPlan() {
        for (FarmSnapshot snapshot : List.of(FarmFixtures.field(Map.of()), FarmFixtures.field(Map.of(), "###"))) {
            FarmPlan plan = plan(snapshot);
            assertTrue(plan.steps().isEmpty());
            assertEquals("nothing to do", plan.describe());
        }
    }

    @Test
    void maxStepsCapsThePlan() {
        FarmSnapshot snapshot = FarmFixtures.field(Map.of(WHEAT_SEEDS, 40), "R".repeat(40));
        FarmPlan plan = FarmPlanner.plan(snapshot, new FarmGoal(null, false, false, 0, 10), FarmFixtures.book());
        assertEquals(10, plan.steps().size());
    }

    @Test
    void isDeterministic() {
        FarmSnapshot snapshot = FarmFixtures.field(Map.of(WHEAT_SEEDS, 4), "RgR", "kRr");
        assertEquals(plan(snapshot).steps(), plan(snapshot).steps());
    }

    @Test
    void growingCellsAreLeftAlone() {
        assertTrue(plan(FarmFixtures.field(Map.of(), "gk")).steps().isEmpty());
    }

    private static List<FarmStep.Sow> sows(FarmPlan plan) {
        return plan.steps().stream().filter(FarmStep.Sow.class::isInstance).map(FarmStep.Sow.class::cast).toList();
    }

    private static List<FarmStep.NeedSeeds> needs(FarmPlan plan) {
        return plan.steps().stream().filter(FarmStep.NeedSeeds.class::isInstance)
                .map(FarmStep.NeedSeeds.class::cast).toList();
    }

    private static long count(FarmPlan plan, Class<? extends FarmStep> type) {
        return plan.steps().stream().filter(type::isInstance).count();
    }

    @Test
    void sowsEmptyFarmlandWithCarriedSeeds() {
        FarmPlan plan = plan(FarmFixtures.field(Map.of(WHEAT_SEEDS, 2), ".."));
        assertEquals(2, sows(plan).size());
        assertTrue(sows(plan).stream().allMatch(sow -> sow.seed().equals(WHEAT_SEEDS)));
        assertEquals(0, plan.unsown());
        assertTrue(needs(plan).isEmpty());
    }

    @Test
    void foodNeedHighPrefersCarrots() {
        Map<String, Integer> seeds = Map.of(WHEAT_SEEDS, 5, FarmFixtures.CARROT, 5);
        FarmSnapshot hungry = FarmFixtures.withFoodNeed(FarmFixtures.field(seeds, ".."), FoodNeed.HIGH);
        assertTrue(sows(plan(hungry)).stream().allMatch(sow -> sow.seed().equals(FarmFixtures.CARROT)));
        assertEquals(2, sows(plan(hungry)).size());
    }

    @Test
    void diversityPrefersTheLeastRepresentedCrop() {
        Map<String, Integer> seeds = Map.of(WHEAT_SEEDS, 5, FarmFixtures.CARROT, 5);
        FarmPlan plan = plan(FarmFixtures.field(seeds, "kk."));
        assertEquals(WHEAT_SEEDS, sows(plan).get(0).seed());
    }

    @Test
    void soilConstrainsChoice() {
        FarmPlan soulSand = plan(FarmFixtures.field(Map.of(WHEAT_SEEDS, 3), "S"));
        assertTrue(sows(soulSand).isEmpty());
        assertEquals(1, soulSand.unsown());
        FarmPlan farmland = plan(FarmFixtures.field(Map.of(FarmFixtures.NETHER_WART, 3), "."));
        assertTrue(sows(farmland).isEmpty());
        assertEquals(1, farmland.unsown());
    }

    @Test
    void explicitCropWins() {
        Map<String, Integer> seeds = Map.of(WHEAT_SEEDS, 3, FarmFixtures.CARROT, 3);
        FarmSnapshot field = FarmFixtures.field(seeds, ".");
        FarmPlan carrots = FarmPlanner.plan(field, new FarmGoal(FarmFixtures.CARROT, false, false, 0, 512),
                FarmFixtures.book());
        assertEquals(FarmFixtures.CARROT, sows(carrots).get(0).seed());

        FarmSnapshot onlyWheat = FarmFixtures.field(Map.of(WHEAT_SEEDS, 3), ".");
        FarmPlan missing = FarmPlanner.plan(onlyWheat, new FarmGoal(FarmFixtures.CARROT, false, false, 0, 512),
                FarmFixtures.book());
        assertEquals(1, missing.unsown());
        assertEquals(List.of(new FarmStep.NeedSeeds(FarmFixtures.CARROT, 1)), needs(missing));
    }

    @Test
    void shortageKeepsUsefulPartialPlan() {
        FarmPlan plan = plan(FarmFixtures.field(Map.of(), "RR.."));
        assertEquals(2, count(plan, FarmStep.Harvest.class));
        assertEquals(2, count(plan, FarmStep.Replant.class));
        assertEquals(2, plan.unsown());
        assertEquals(List.of(new FarmStep.NeedSeeds(WHEAT_SEEDS, 2)), needs(plan));
    }

    @Test
    void oneCarriedSeedSowsOneOfTwoCells() {
        FarmPlan plan = plan(FarmFixtures.field(Map.of(WHEAT_SEEDS, 1), "RR.."));
        assertEquals(1, sows(plan).size());
        assertEquals(1, plan.unsown());
        assertEquals(List.of(new FarmStep.NeedSeeds(WHEAT_SEEDS, 1)), needs(plan));
    }

    @Test
    void noSeedsAtAllStillHarvests() {
        FarmPlan plan = plan(FarmFixtures.field(Map.of(), "R."));
        assertEquals(1, count(plan, FarmStep.Harvest.class));
        assertEquals(1, count(plan, FarmStep.Replant.class));
        assertEquals(1, plan.unsown());
        assertEquals(1, needs(plan).size());
    }

    @Test
    void replantShortageIsReported() {
        FarmPlan plan = FarmPlanner.plan(FarmFixtures.field(Map.of(), "R"), FarmGoal.defaults(), FarmFixtures.book(0.0));
        assertEquals(1, count(plan, FarmStep.Replant.class));
        assertEquals(List.of(new FarmStep.NeedSeeds(WHEAT_SEEDS, 1)), needs(plan));
    }

    @Test
    void reserveBlocksSowingNotReplanting() {
        FarmPlan plan = FarmPlanner.plan(FarmFixtures.field(Map.of(WHEAT_SEEDS, 2), "R."),
                new FarmGoal(null, false, false, 2, 512), FarmFixtures.book());
        assertEquals(1, count(plan, FarmStep.Replant.class));
        assertTrue(sows(plan).isEmpty());
        assertEquals(1, plan.unsown());
    }

    @Test
    void usesExpectedHarvestSeedsForSowing() {
        FarmPlan plan = FarmPlanner.plan(FarmFixtures.field(Map.of(), "R."), FarmGoal.defaults(), FarmFixtures.book(3.0));
        assertEquals(1, count(plan, FarmStep.Replant.class));
        assertEquals(1, sows(plan).size());
        assertEquals(0, plan.unsown());
    }
}
