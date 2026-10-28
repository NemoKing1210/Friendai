package dev.friendai.features.mining;

import dev.friendai.features.navigation.pathing.CellView;
import dev.friendai.features.navigation.pathing.CostModel;
import dev.friendai.features.navigation.pathing.GoalReach;
import dev.friendai.features.navigation.pathing.Node;
import dev.friendai.features.navigation.pathing.PathGoal;
import dev.friendai.features.navigation.pathing.Pos;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Reach goal that only accepts safe, dry stances around explicitly selected resource blocks. */
public final class MiningGoal implements PathGoal {

    private final List<Pos> targets;
    private final double reach;
    private final double eyeHeight;
    private final Set<Pos> protectedSupports;
    private final Set<Node> excludedStances;
    private final boolean allowOccludedApproach;

    public MiningGoal(List<Pos> targets, double reach, double eyeHeight, Set<Pos> protectedSupports) {
        this(targets, reach, eyeHeight, protectedSupports, Set.of());
    }

    public MiningGoal(
            List<Pos> targets, double reach, double eyeHeight, Set<Pos> protectedSupports, Set<Node> excludedStances) {
        this(targets, reach, eyeHeight, protectedSupports, excludedStances, false);
    }

    public MiningGoal(
            List<Pos> targets,
            double reach,
            double eyeHeight,
            Set<Pos> protectedSupports,
            Set<Node> excludedStances,
            boolean allowOccludedApproach) {
        this.targets = List.copyOf(targets);
        this.reach = reach;
        this.eyeHeight = eyeHeight;
        this.protectedSupports = Set.copyOf(protectedSupports);
        this.excludedStances = Set.copyOf(excludedStances);
        this.allowOccludedApproach = allowOccludedApproach;
    }

    @Override
    public boolean isGoal(Node node, CellView view) {
        return allowOccludedApproach
                ? approachAt(node, view).isPresent()
                : targetAt(node, view).isPresent();
    }

    private Optional<Pos> approachAt(Node node, CellView view) {
        if (excludedStances.contains(node)) {
            return Optional.empty();
        }
        for (Pos target : targets) {
            double dx = target.x() + 0.5 - (node.x() + 0.5);
            double dy = target.y() + 0.5 - (node.y() + eyeHeight);
            double dz = target.z() + 0.5 - (node.z() + 0.5);
            if (dx * dx + dy * dy + dz * dz <= reach * reach
                    && MiningSafety.canApproach(view, node, target, protectedSupports)) {
                return Optional.of(target);
            }
        }
        return Optional.empty();
    }

    /** First target that is safe and visible from this stance, if any. */
    public Optional<Pos> targetAt(Node node, CellView view) {
        if (excludedStances.contains(node)) {
            return Optional.empty();
        }
        for (Pos target : targets) {
            if (MiningSafety.canBreak(view, node, target, protectedSupports)
                    && new GoalReach(List.of(target), reach, eyeHeight).reachedTarget(node, view) != null) {
                return Optional.of(target);
            }
        }
        return Optional.empty();
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
