package dev.friendai.features.mining.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class FellPlannerTest {
    private static final TreeCell STAND = new TreeCell(8, 2, 8);

    private static List<TreeCell> trunk(int x, int z, int baseY, int height) {
        List<TreeCell> logs = new ArrayList<>();
        for (int i = 0; i < height; i++) {
            logs.add(new TreeCell(x, baseY + i, z));
        }
        return logs;
    }

    /** A 2x2 trunk: four columns, one log each per layer. */
    private static List<TreeCell> square(int x, int z, int baseY, int layers) {
        List<TreeCell> logs = new ArrayList<>();
        for (int layer = 0; layer < layers; layer++) {
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    logs.add(new TreeCell(x + dx, baseY + layer, z + dz));
                }
            }
        }
        return logs;
    }

    private static FellPlan.Climb best(List<TreeCell> logs, TreeCell stand, int limit) {
        return FellPlanner.plan(logs, stand, limit).climbs().get(0);
    }

    @Test
    void twoByTwoTrunkCutsEveryLogOnce() {
        List<TreeCell> logs = square(0, 0, 2, 12);
        FellPlan plan = FellPlanner.plan(logs, STAND, 0);
        FellPlan.Climb climb = plan.climbs().get(0);
        assertEquals(48, plan.cutOrder().size(), "every log is cut");
        assertEquals(48, plan.cutOrder().stream().distinct().count(), "no log twice");
        assertTrue(logs.containsAll(plan.cutOrder()), "only the tree's logs");
        assertTrue(
                plan.cutOrder().get(0).y() == 13,
                "the crown goes first: " + plan.cutOrder().get(0));
        for (int i = 1; i < plan.cutOrder().size(); i++) {
            assertTrue(
                    plan.cutOrder().get(i).y() <= plan.cutOrder().get(i - 1).y(),
                    "order must not climb: " + plan.cutOrder());
        }
        assertFalse(plan.truncated());
        assertEquals(0, plan.unreachableLogs());
        assertEquals(plan.cutOrder(), climb.logs());
    }

    @Test
    void everyLogIsWithinReachOfItsStage() {
        List<TreeCell> logs = square(0, 0, 2, 24);
        FellPlan.Climb climb = best(logs, STAND, 0);
        for (FellPlan.Stage stage : climb.stages()) {
            for (TreeCell log : stage.logs()) {
                double dx = (climb.column().x() + 0.5) - (log.x() + 0.5);
                double dz = (climb.column().z() + 0.5) - (log.z() + 0.5);
                double dy = stage.feetY() + FellPlanner.EYE_HEIGHT - (log.y() + 0.5);
                assertTrue(
                        dx * dx + dy * dy + dz * dz <= FellPlanner.REACH * FellPlanner.REACH + 1.0e-9,
                        "unreachable " + log + " from feet " + stage.feetY() + " at " + climb.column());
            }
        }
    }

    @Test
    void shortTreeNeedsNoScaffold() {
        FellPlan.Climb climb = best(trunk(0, 0, 2, 5), STAND, 0);
        assertEquals(2, climb.climbFeetY());
        assertEquals(0, climb.scaffoldNeeded());
        assertEquals(5, climb.logs().size());
    }

    @Test
    void tallTreeClimbsOnlyTheBlocksItNeeds() {
        FellPlan.Climb climb = best(trunk(0, 0, 2, 24), STAND, 0);
        assertEquals(21, climb.climbFeetY(), "lowest stance that reaches the top log");
        assertEquals(19, climb.scaffoldNeeded());
    }

    @Test
    void limitTakesTheHighestLogs() {
        FellPlan plan = FellPlanner.plan(trunk(0, 0, 2, 20), STAND, 3);
        assertEquals(3, plan.cutOrder().size());
        assertEquals(
                List.of(21, 20, 19), plan.cutOrder().stream().map(TreeCell::y).toList());
        assertTrue(plan.truncated());
        assertEquals(20, plan.totalLogs());
    }

    @Test
    void stagesDescendAndNeverLeaveTheGround() {
        FellPlan.Climb climb = best(square(0, 0, 2, 24), STAND, 0);
        int previous = Integer.MAX_VALUE;
        for (FellPlan.Stage stage : climb.stages()) {
            assertTrue(stage.feetY() <= previous, "a stage may not climb: " + climb.stages());
            assertTrue(stage.feetY() >= climb.groundFeetY(), "below the ground: " + stage.feetY());
            assertFalse(stage.logs().isEmpty(), "an empty stage is pointless");
            previous = stage.feetY();
        }
        assertEquals(climb.climbFeetY(), climb.stages().get(0).feetY());
        FellPlan.Stage last = climb.stages().get(climb.stages().size() - 1);
        assertTrue(last.logs().stream().anyMatch(log -> log.y() == 2), "the base layer is cut last");
    }

    @Test
    void columnIsBesideTheBaseAndClearOfLogs() {
        List<TreeCell> logs = square(0, 0, 2, 10);
        FellPlan plan = FellPlanner.plan(logs, new TreeCell(10, 2, 0), 0);
        FellPlan.Climb climb = plan.climbs().get(0);
        assertEquals(
                new TreeCell(2, 2, 0),
                climb.column(),
                plan.climbs().stream().map(FellPlan.Climb::column).toList().toString());
        for (TreeCell log : logs) {
            assertNotEquals(climb.column().columnKey(), log.columnKey(), "a climb inside a log column");
        }
    }

    @Test
    void branchLogsWithinReachAreIncluded() {
        List<TreeCell> logs = new ArrayList<>(trunk(0, 0, 2, 16));
        logs.add(new TreeCell(3, 14, 0)); // reachable from the ring-1 column
        logs.add(new TreeCell(6, 14, 0)); // no stance gets within four blocks
        FellPlan plan = FellPlanner.plan(logs, new TreeCell(0, 2, -8), 0);
        assertTrue(plan.cutOrder().contains(new TreeCell(3, 14, 0)), "a reachable branch is cut");
        assertFalse(plan.cutOrder().contains(new TreeCell(6, 14, 0)), "an out-of-reach branch is not");
        assertEquals(1, plan.unreachableLogs());
        assertEquals(18, plan.totalLogs());
    }

    @Test
    void noFreeColumnYieldsNoClimb() {
        List<TreeCell> logs = new ArrayList<>(trunk(0, 0, 2, 11));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx != 0 || dz != 0) {
                    logs.add(new TreeCell(dx, 10, dz)); // a low canopy covers every ring column
                }
            }
        }
        FellPlan plan = FellPlanner.plan(logs, STAND, 0);
        assertTrue(plan.climbs().isEmpty(), "columns: " + plan.climbs());
        assertTrue(plan.cutOrder().isEmpty());
    }

    @Test
    void sameInputSamePlan() {
        List<TreeCell> logs = square(0, 0, 2, 9);
        FellPlan first = FellPlanner.plan(logs, STAND, 0);
        List<TreeCell> shuffled = new ArrayList<>(logs);
        Collections.shuffle(shuffled, new java.util.Random(7));
        assertEquals(first, FellPlanner.plan(shuffled, STAND, 0));
        assertEquals(first, FellPlanner.plan(logs, STAND, 0));
    }

    @Test
    void branchesInRingOneColumnsLeaveTheOtherSides() {
        List<TreeCell> logs = new ArrayList<>();
        for (int y = 2; y <= 17; y++) {
            logs.add(new TreeCell(6, y, 6));
        }
        logs.add(new TreeCell(5, 14, 6));
        logs.add(new TreeCell(7, 13, 6));
        FellPlan plan = FellPlanner.plan(logs, new TreeCell(2, 2, 2), 0);
        assertFalse(plan.climbs().isEmpty(), "climbs: " + plan.climbs());
        assertEquals(18, plan.cutOrder().size(), "cut: " + plan.cutOrder());
        assertEquals(0, plan.unreachableLogs());
    }

    /** The stance is kept until the next log is out of reach: a 24-layer trunk is a handful of stages, not 96. */
    @Test
    void oneStanceCoversManyLogs() {
        List<TreeCell> logs = square(0, 0, 2, 24);
        FellPlan.Climb climb = best(logs, STAND, 0);
        assertEquals(96, climb.logs().size(), "every log is cut exactly once");
        assertTrue(
                climb.stages().size() >= 1 && climb.stages().size() < logs.size() / 2,
                "a stance per log would be the regression this guards: "
                        + climb.stages().size());
        List<TreeCell> flattened = new ArrayList<>();
        for (FellPlan.Stage stage : climb.stages()) {
            flattened.addAll(stage.logs());
        }
        assertEquals(climb.logs(), flattened, "the stages are the cut order");
    }
}
