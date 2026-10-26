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
}
