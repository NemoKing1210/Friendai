package dev.friendai.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.pathing.CostModel;
import dev.friendai.pathing.Move;
import dev.friendai.pathing.Node;
import dev.friendai.pathing.PathStep;
import dev.friendai.pathing.Pos;
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
    void alternateSideStanceCanReachUnderfootOre() {
        GridCellView view = miningFloor();
        MiningGoal goal = new MiningGoal(List.of(new Pos(2, 1, 1)), 4, 1.62, Set.of());

        assertTrue(goal.isGoal(new Node(1, 2, 1), view));
    }

    @Test
    void corridorCannotRemoveRecordedSupport() {
        GridCellView view = new GridCellView("#####", ".....", ".....");
        Pos support = new Pos(1, 0, 0);
        PathStep step = new PathStep(new Node(0, 1, 0), new Node(1, 1, 0), Move.DIG,
                List.of(support), List.of(), 10, 0);

        assertTrue(new MiningPathPolicy(view, new MiningBounds(new Node(0, 1, 0), 4), Set.of(support),
                CostModel.DEFAULTS).prepare(view, step).isEmpty());
    }

    @Test
    void targetAdjacentToWaterOrFallingBlockIsRejected() {
        GridCellView water = new GridCellView("#####/#####/#####", "#####/#####/#####", "...../..~../.....", "...../...../.....");
        GridCellView falling = new GridCellView("#####/#####/#####", "#####/#####/#####", "...../..g../.....", "...../...../.....");
        MiningGoal waterGoal = new MiningGoal(List.of(new Pos(2, 1, 1)), 4, 1.62, Set.of());
        MiningGoal fallingGoal = new MiningGoal(List.of(new Pos(2, 1, 1)), 4, 1.62, Set.of());

        assertFalse(waterGoal.isGoal(new Node(1, 2, 1), water));
        assertFalse(fallingGoal.isGoal(new Node(1, 2, 1), falling));
    }

    @Test
    void descendingStepAddsReverseHeadroom() {
        GridCellView view = new GridCellView("####", "####", "..#.", "..#.", "....");
        Pos feet = new Pos(2, 1, 0);
        Pos head = new Pos(2, 2, 0);
        Pos reverseHeadroom = new Pos(2, 3, 0);
        PathStep descend = new PathStep(new Node(1, 2, 0), new Node(2, 1, 0), Move.DIG_DESCEND,
                List.of(feet, head), List.of(), 20, 0);

        var prepared = new MiningPathPolicy(view, new MiningBounds(descend.from(), 4), Set.of(), CostModel.DEFAULTS)
                .prepare(view, descend);

        assertTrue(prepared.isPresent());
        assertEquals(3, prepared.get().breaks().size());
        assertTrue(prepared.get().breaks().contains(reverseHeadroom));
        assertEquals(20 + view.at(reverseHeadroom).breakTicks() + CostModel.DEFAULTS.breakExtra(),
                prepared.get().cost(), 1e-9);
    }

    @Test
    void turningStairHasClearReverseAscent() {
        GridCellView view = new GridCellView(
                "####/####/####/####",
                "..../..#./..../....",
                "..../..../..#./....",
                "..../..../..../....",
                "..../..../..../....");
        PathStep first = new PathStep(new Node(1, 1, 1), new Node(2, 2, 1), Move.DIG_ASCEND,
                List.of(), List.of(), 7, 0);
        PathStep turn = new PathStep(new Node(2, 2, 1), new Node(2, 3, 2), Move.DIG_ASCEND,
                List.of(), List.of(), 7, 0);

        assertTrue(MiningSafety.reversible(view, first));
        assertTrue(MiningSafety.reversible(view, turn));
    }

    @Test
    void miningPolicyRejectsMultiBlockDropSwimPillarAndOutOfBounds() {
        GridCellView view = new GridCellView("#####", ".....", ".....", ".....");
        MiningBounds bounds = new MiningBounds(new Node(1, 2, 0), 1);
        MiningPathPolicy policy = new MiningPathPolicy(view, bounds, Set.of(), CostModel.DEFAULTS);
        PathStep drop = new PathStep(new Node(1, 3, 0), new Node(2, 1, 0), Move.DESCEND,
                List.of(), List.of(), 4, 0);
        PathStep swim = new PathStep(new Node(1, 2, 0), new Node(2, 2, 0), Move.SWIM,
                List.of(), List.of(), 9, 0);
        PathStep pillar = new PathStep(new Node(1, 2, 0), new Node(1, 3, 0), Move.PILLAR,
                List.of(), List.of(new Pos(1, 1, 0)), 10, 0);
        PathStep outside = new PathStep(new Node(1, 2, 0), new Node(3, 2, 0), Move.WALK,
                List.of(), List.of(), 4, 0);
        PathStep gapJump = new PathStep(new Node(1, 2, 0), new Node(3, 2, 0), Move.JUMP_GAP,
                List.of(), List.of(), 6, 0);
        PathStep bridge = new PathStep(new Node(1, 2, 0), new Node(2, 2, 0), Move.BRIDGE,
                List.of(), List.of(new Pos(2, 1, 0)), 9, 0);

        assertTrue(policy.prepare(view, drop).isEmpty());
        assertTrue(policy.prepare(view, swim).isEmpty());
        assertTrue(policy.prepare(view, pillar).isEmpty());
        assertTrue(policy.prepare(view, outside).isEmpty());
        assertTrue(policy.prepare(view, gapJump).isEmpty());
        assertTrue(policy.prepare(view, bridge).isEmpty());
    }

    @Test
    void supportedHorizontalDigAndDrySingleBlockDescentRemainLegal() {
        GridCellView tunnel = new GridCellView(
                "#####/#####/#####",
                "...../..#../.....",
                "...../...../.....");
        PathStep dig = new PathStep(new Node(1, 1, 1), new Node(2, 1, 1), Move.DIG,
                List.of(new Pos(2, 1, 1)), List.of(), 10, 0);

        GridCellView stair = new GridCellView(
                "#####/#####/#####",
                ".#.../.#.../.#...",
                "...../...../.....",
                "...../...../.....");
        PathStep descend = new PathStep(new Node(1, 2, 1), new Node(2, 1, 1), Move.DESCEND,
                List.of(), List.of(), 6, 0);

        assertTrue(MiningSafety.reversible(tunnel, dig));
        assertTrue(MiningSafety.reversible(stair, descend));
    }

    @Test
    void descentWithBlockedReverseClearanceIsRejected() {
        GridCellView stair = new GridCellView(
                "#####/#####/#####",
                ".#.../.#.../.#...",
                "...../...../.....",
                "..p../..p../..p..");
        PathStep descend = new PathStep(new Node(1, 2, 1), new Node(2, 1, 1), Move.DESCEND,
                List.of(), List.of(), 6, 0);

        assertTrue(new MiningPathPolicy(stair, new MiningBounds(descend.from(), 4), Set.of(), CostModel.DEFAULTS)
                .prepare(stair, descend).isEmpty());
    }

    private static GridCellView miningFloor() {
        return new GridCellView("#####/#####/#####", "#####/#####/#####", "...../...../.....", "...../...../.....");
    }
}
