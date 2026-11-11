package dev.friendai.features.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.spatial.CostModel;
import dev.friendai.foundation.spatial.Move;
import dev.friendai.foundation.spatial.Node;
import dev.friendai.foundation.spatial.PathSearch;
import dev.friendai.foundation.spatial.PathStep;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.foundation.spatial.SearchOptions;
import dev.friendai.foundation.spatial.SearchResult;
import dev.friendai.testutil.GridCellView;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MiningSafetyTest {

    @Test
    void supportTargetIsNotAValidStance() {
        GridCellView view = miningFloor();
        MiningGoal goal = new MiningGoal(List.of(new Pos(2, 1, 1)), 4, 1.62, Set.of());

        assertFalse(goal.isGoal(new Node(2, 2, 1), view));
    }

    @Test
    void theBlockUnderTheStanceIsNeverABreakTarget() {
        GridCellView view = new GridCellView(
                "#######/#######/#######",
                "#######/#######/#######",
                "......./......./.......",
                "......./......./.......");
        Node stance = new Node(3, 2, 1);

        assertFalse(
                MiningSafety.canBreak(view, stance, new Pos(3, 1, 1), Set.of()),
                "a dig-down must not remove the block it stands on");
        assertTrue(
                MiningSafety.canBreak(view, stance, new Pos(1, 1, 1), Set.of()),
                "a block away from the feet stays a target");
    }

    @Test
    void adjacentSideStanceDoesNotOverlapUnderfootOreButFartherStanceCanReach() {
        GridCellView view = miningFloor();
        MiningGoal goal = new MiningGoal(List.of(new Pos(2, 1, 1)), 4, 1.62, Set.of());

        assertFalse(goal.isGoal(new Node(1, 2, 1), view));
        assertTrue(goal.isGoal(new Node(0, 2, 1), view));
    }

    @Test
    void rejectedLiveStanceDoesNotHideTheTargetFromAnotherStance() {
        GridCellView view = miningFloor();
        Node rejected = new Node(1, 2, 1);
        MiningGoal goal = new MiningGoal(List.of(new Pos(2, 1, 1)), 4, 1.62, Set.of(), Set.of(rejected));

        assertFalse(goal.isGoal(rejected, view));
        assertTrue(goal.isGoal(new Node(0, 2, 1), view));
    }

    @Test
    void corridorCannotRemoveRecordedSupport() {
        GridCellView view = new GridCellView("#####", ".....", ".....");
        Pos support = new Pos(1, 0, 0);
        PathStep step =
                new PathStep(new Node(0, 1, 0), new Node(1, 1, 0), Move.DIG, List.of(support), List.of(), 10, 0);

        assertTrue(
                new MiningPathPolicy(view, new MiningBounds(new Node(0, 1, 0), 4), Set.of(support), CostModel.DEFAULTS)
                        .prepare(view, step)
                        .isEmpty());
    }

    @Test
    void aPlannedStairStepIsBreakableFromTheStepAbove() {
        GridCellView view =
                new GridCellView("#####/#####/#####", "#####/#####/#####", "...../...../.....", "...../...../.....");
        Node stance = new Node(1, 2, 1);
        Pos step = new Pos(2, 1, 1);

        assertFalse(MiningSafety.canBreak(view, stance, step, Set.of()), "a floor resource keeps the side-stance rule");
        assertTrue(
                MiningSafety.canBreak(view, stance, step, Set.of(), true),
                "a planned step may be carved from the step above");
        assertTrue(
                MiningSafety.canApproach(view, stance, step, Set.of(), true),
                "a planned step may also be approached from the step above");
    }

    @Test
    void aStepDownNeedsAnOpenHeadAndALanding() {
        Node stance = new Node(1, 2, 1);
        Pos step = new Pos(2, 1, 1);
        GridCellView covered =
                new GridCellView("#####/#####/#####", "#####/#####/#####", "...../..#../.....", "...../...../.....");
        GridCellView noLanding =
                new GridCellView("#####/##.##/#####", "#####/#####/#####", "...../...../.....", "...../...../.....");

        assertFalse(
                MiningSafety.canBreak(covered, stance, step, Set.of(), true),
                "a solid head cell is not a walkable step");
        assertFalse(
                MiningSafety.canBreak(noLanding, stance, step, Set.of(), true),
                "a step with nothing under it is not a landing");
    }

    @Test
    void aCarvedStairGoalAcceptsTheStepAbove() {
        GridCellView view =
                new GridCellView("#####/#####/#####", "#####/#####/#####", "...../...../.....", "...../...../.....");
        Pos step = new Pos(2, 1, 1);
        Node above = new Node(1, 2, 1);

        assertFalse(new MiningGoal(List.of(step), 4, 1.62, Set.of()).isGoal(above, view));
        assertTrue(
                new MiningGoal(List.of(step), 4, 1.62, Set.of(), Set.of(), false, true).isGoal(above, view),
                "a carved passage accepts its own next step");
    }

    @Test
    void targetAdjacentToWaterIsRejected() {
        GridCellView water =
                new GridCellView("#####/#####/#####", "#####/#####/#####", "...../.~.../.....", "...../...../.....");
        MiningGoal goal = new MiningGoal(List.of(new Pos(1, 1, 1)), 4, 1.62, Set.of());

        assertFalse(goal.isGoal(new Node(3, 2, 1), water), "water rests on the target and would flood the opening");
        assertTrue(goal.isGoal(new Node(3, 2, 1), miningFloor()), "the same stance reaches the dry target");
    }

    @Test
    void aPlantOnSandIsATargetFromBesideIt() {
        // A dead bush on red sand: the block under the target falls, but breaking the plant moves nothing.
        GridCellView desert =
                new GridCellView("#####/#####/#####", "ggggg/ggggg/ggggg", "...../...,./.....", "...../...../.....");
        MiningGoal goal = new MiningGoal(List.of(new Pos(3, 2, 1)), 4, 1.62, Set.of());

        assertTrue(goal.isGoal(new Node(2, 2, 1), desert));
    }

    @Test
    void aSandFloorIsMinedFromTwoCellsAway() {
        GridCellView desert =
                new GridCellView("#####/#####/#####", "ggggg/ggggg/ggggg", "...../...../.....", "...../...../.....");
        MiningGoal goal = new MiningGoal(List.of(new Pos(3, 1, 1)), 4, 1.62, Set.of());

        assertTrue(goal.isGoal(new Node(1, 2, 1), desert), "the sand column falls clear of the stance");
        assertFalse(goal.isGoal(new Node(2, 2, 1), desert), "a floor block beside the feet keeps the side-stance rule");
    }

    @Test
    void aFallingColumnOverTheBodyIsNotATarget() {
        // A tunnel with a stone ceiling; gravel sits in the ceiling at x=2 and on top of the ceiling at x=3.
        GridCellView tunnel = new GridCellView(
                "#####/#####/#####",
                "...../...../.....",
                "...../...../.....",
                "#####/##g##/#####",
                "#####/###g#/#####",
                "#####/#####/#####");
        Pos gravel = new Pos(2, 3, 1);
        Pos stoneUnderGravel = new Pos(3, 3, 1);

        assertFalse(
                MiningSafety.canBreak(tunnel, new Node(2, 1, 1), gravel, Set.of()),
                "gravel overhead would drop onto the body");
        assertTrue(
                MiningSafety.canBreak(tunnel, new Node(1, 1, 1), gravel, Set.of()),
                "from one cell aside the gravel lands beside the body");
        assertFalse(
                MiningSafety.canBreak(tunnel, new Node(3, 1, 1), stoneUnderGravel, Set.of()),
                "the block that holds gravel up over the head stays");
        assertTrue(
                MiningSafety.canBreak(tunnel, new Node(2, 1, 1), stoneUnderGravel, Set.of()),
                "the same block is safe to take from the next column");
    }

    @Test
    void descendingStepAddsReverseHeadroom() {
        GridCellView view = new GridCellView("####", "####", "..#.", "..#.", "....");
        Pos feet = new Pos(2, 1, 0);
        Pos head = new Pos(2, 2, 0);
        Pos reverseHeadroom = new Pos(2, 3, 0);
        PathStep descend = new PathStep(
                new Node(1, 2, 0), new Node(2, 1, 0), Move.DIG_DESCEND, List.of(feet, head), List.of(), 20, 0);

        var prepared = new MiningPathPolicy(view, new MiningBounds(descend.from(), 4), Set.of(), CostModel.DEFAULTS)
                .prepare(view, descend);

        assertTrue(prepared.isPresent());
        assertEquals(3, prepared.get().breaks().size());
        assertTrue(prepared.get().breaks().contains(reverseHeadroom));
        assertEquals(
                20 + view.at(reverseHeadroom).breakTicks() + CostModel.DEFAULTS.breakExtra(),
                prepared.get().cost(),
                1e-9);
    }

    @Test
    void turningStairHasClearReverseAscent() {
        GridCellView view = new GridCellView(
                "####/####/####/####",
                "..../..#./..../....",
                "..../..../..#./....",
                "..../..../..../....",
                "..../..../..../....");
        PathStep first =
                new PathStep(new Node(1, 1, 1), new Node(2, 2, 1), Move.DIG_ASCEND, List.of(), List.of(), 7, 0);
        PathStep turn = new PathStep(new Node(2, 2, 1), new Node(2, 3, 2), Move.DIG_ASCEND, List.of(), List.of(), 7, 0);

        assertTrue(MiningSafety.reversible(view, first));
        assertTrue(MiningSafety.reversible(view, turn));
    }

    @Test
    void miningPolicyRejectsMultiBlockDropSwimPillarAndOutOfBounds() {
        GridCellView view = new GridCellView("#####", ".....", ".....", ".....");
        MiningBounds bounds = new MiningBounds(new Node(1, 2, 0), 1);
        MiningPathPolicy policy = new MiningPathPolicy(view, bounds, Set.of(), CostModel.DEFAULTS);
        PathStep drop = new PathStep(new Node(1, 3, 0), new Node(2, 1, 0), Move.DESCEND, List.of(), List.of(), 4, 0);
        PathStep swim = new PathStep(new Node(1, 2, 0), new Node(2, 2, 0), Move.SWIM, List.of(), List.of(), 9, 0);
        PathStep pillar = new PathStep(
                new Node(1, 2, 0), new Node(1, 3, 0), Move.PILLAR, List.of(), List.of(new Pos(1, 1, 0)), 10, 0);
        PathStep outside = new PathStep(new Node(1, 2, 0), new Node(3, 2, 0), Move.WALK, List.of(), List.of(), 4, 0);
        PathStep gapJump =
                new PathStep(new Node(1, 2, 0), new Node(3, 2, 0), Move.JUMP_GAP, List.of(), List.of(), 6, 0);
        PathStep bridge = new PathStep(
                new Node(1, 2, 0), new Node(2, 2, 0), Move.BRIDGE, List.of(), List.of(new Pos(2, 1, 0)), 9, 0);

        assertTrue(policy.prepare(view, drop).isEmpty());
        assertTrue(policy.prepare(view, swim).isEmpty());
        assertTrue(policy.prepare(view, pillar).isEmpty());
        assertTrue(policy.prepare(view, outside).isEmpty());
        assertTrue(policy.prepare(view, gapJump).isEmpty());
        assertTrue(policy.prepare(view, bridge).isEmpty());
    }

    @Test
    void aStanceWithNothingUnderItCanBeLeftButNotEntered() {
        // The companion broke the block under itself: its own cell is air with the rim holding its box up.
        GridCellView view = new GridCellView(".....", "..#..", ".....", ".....");
        PathStep away = new PathStep(new Node(1, 2, 0), new Node(2, 2, 0), Move.WALK, List.of(), List.of(), 4, 0);
        PathStep into = new PathStep(new Node(2, 2, 0), new Node(1, 2, 0), Move.WALK, List.of(), List.of(), 4, 0);

        assertTrue(MiningSafety.reversible(view, away));
        assertFalse(MiningSafety.reversible(view, into));
    }

    @Test
    void supportedHorizontalDigAndDrySingleBlockDescentRemainLegal() {
        GridCellView tunnel = new GridCellView("#####/#####/#####", "...../..#../.....", "...../...../.....");
        PathStep dig = new PathStep(
                new Node(1, 1, 1), new Node(2, 1, 1), Move.DIG, List.of(new Pos(2, 1, 1)), List.of(), 10, 0);

        GridCellView stair =
                new GridCellView("#####/#####/#####", ".#.../.#.../.#...", "...../...../.....", "...../...../.....");
        PathStep descend = new PathStep(new Node(1, 2, 1), new Node(2, 1, 1), Move.DESCEND, List.of(), List.of(), 6, 0);

        assertTrue(MiningSafety.reversible(tunnel, dig));
        assertTrue(MiningSafety.reversible(stair, descend));
    }

    @Test
    void supportedRouteCanReachResourceBuriedInAHill() {
        String[] layers = hillLayers();
        GridCellView view = new GridCellView(layers);
        Pos target = new Pos(7, 3, 7);
        Node start = new Node(2, 2, 2);
        MiningBounds bounds = new MiningBounds(start, 8);
        MiningGoal goal = new MiningGoal(List.of(target), 1.5, 1.62, Set.of(), Set.of(), true);
        MiningPathPolicy policy = new MiningPathPolicy(view, bounds, Set.of(), CostModel.DEFAULTS);
        PathSearch search =
                new PathSearch(view, CostModel.DEFAULTS, goal, start, new SearchOptions(20_000, 8, 0, 1), policy);

        SearchResult result = search.step(Long.MAX_VALUE, () -> 0L);

        assertEquals(SearchResult.Status.FOUND, result.status(), "expanded=" + search.expanded());
        assertTrue(
                result.path().steps().stream().anyMatch(step -> !step.breaks().isEmpty()));
    }

    private static String[] hillLayers() {
        String[] layers = new String[7];
        for (int y = 0; y < layers.length; y++) {
            StringBuilder layer = new StringBuilder();
            for (int z = 0; z < 14; z++) {
                if (z > 0) layer.append('/');
                for (int x = 0; x < 14; x++) {
                    char cell = y == 1 ? '#' : '.';
                    if (y >= 2 && y <= 4 && x >= 5 && x <= 9 && z >= 5 && z <= 9) cell = 'd';
                    if (x == 7 && y == 3 && z == 7) cell = 'd';
                    layer.append(cell);
                }
            }
            layers[y] = layer.toString();
        }
        return layers;
    }

    @Test
    void descentWithBlockedReverseClearanceIsRejected() {
        GridCellView stair =
                new GridCellView("#####/#####/#####", ".#.../.#.../.#...", "...../...../.....", "..p../..p../..p..");
        PathStep descend = new PathStep(new Node(1, 2, 1), new Node(2, 1, 1), Move.DESCEND, List.of(), List.of(), 6, 0);

        assertTrue(new MiningPathPolicy(stair, new MiningBounds(descend.from(), 4), Set.of(), CostModel.DEFAULTS)
                .prepare(stair, descend)
                .isEmpty());
    }

    private static GridCellView miningFloor() {
        return new GridCellView("#####/#####/#####", "#####/#####/#####", "...../...../.....", "...../...../.....");
    }
}
