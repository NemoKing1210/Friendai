package dev.friendai.mining;

import dev.friendai.pathing.CellView;
import dev.friendai.pathing.CostModel;
import dev.friendai.pathing.GoalReach;
import dev.friendai.pathing.Node;
import dev.friendai.pathing.PathGoal;
import dev.friendai.pathing.Pos;
import java.util.List;
import java.util.Set;

/** Reach goal that only accepts safe, dry stances around explicitly selected resource blocks. */
public final class MiningGoal implements PathGoal {

    private final List<Pos> targets;
    private final double reach;
    private final double eyeHeight;
    private final Set<Pos> protectedSupports;

    public MiningGoal(List<Pos> targets, double reach, double eyeHeight, Set<Pos> protectedSupports) {
        this.targets = List.copyOf(targets);
        this.reach = reach;
        this.eyeHeight = eyeHeight;
        this.protectedSupports = Set.copyOf(protectedSupports);
    }

    @Override
    public boolean isGoal(Node node, CellView view) {
        for (Pos target : targets) {
            if (MiningSafety.canBreak(view, node, target, protectedSupports)
                    && new GoalReach(List.of(target), reach, eyeHeight).reachedTarget(node, view) != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double heuristic(Node node, CostModel costs) {
        return new GoalReach(targets, reach, eyeHeight).heuristic(node, costs);
    }

    @Override
    public boolean protects(Pos pos) {
        return targets.contains(pos) || protectedSupports.contains(pos);
    }
}
