package dev.friendai.mining;

import dev.friendai.pathing.CellView;
import dev.friendai.pathing.CostModel;
import dev.friendai.pathing.Move;
import dev.friendai.pathing.PathPolicy;
import dev.friendai.pathing.PathStep;
import dev.friendai.pathing.Pos;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** Restricts pathfinding to dry, cardinal, reversible mining movement inside the original radius. */
public final class MiningPathPolicy implements PathPolicy {

    private final CellView view;
    private final MiningBounds bounds;
    private final Set<Pos> protectedSupports;
    private final CostModel costs;

    public MiningPathPolicy(CellView view, MiningBounds bounds, Set<Pos> protectedSupports, CostModel costs) {
        this.view = view;
        this.bounds = bounds;
        this.protectedSupports = Set.copyOf(protectedSupports);
        this.costs = costs;
    }

    @Override
    public Optional<PathStep> prepare(CellView ignored, PathStep step) {
        if (!bounds.contains(step.from().pos()) || !bounds.contains(step.to().pos()) || !step.places().isEmpty()) {
            return Optional.empty();
        }
        LinkedHashSet<Pos> breaks = new LinkedHashSet<>(step.breaks());
        if (step.move() == Move.DESCEND || step.move() == Move.DIG_DESCEND) {
            Pos reverseHeadroom = step.to().pos().offset(0, 2, 0);
            if (!view.at(reverseHeadroom).free()) {
                breaks.add(reverseHeadroom);
            }
        }
        for (Pos block : breaks) {
            if (!bounds.contains(block) || !view.at(block).breakable() || protectedSupports.contains(block)) {
                return Optional.empty();
            }
        }
        double cost = step.cost();
        for (Pos block : breaks) {
            if (!step.breaks().contains(block)) {
                cost += view.at(block).breakTicks() + costs.breakExtra();
            }
        }
        PathStep prepared = new PathStep(step.from(), step.to(), step.move(), new ArrayList<>(breaks),
                step.places(), cost, step.headUnderwater());
        return MiningSafety.reversible(view, prepared) ? Optional.of(prepared) : Optional.empty();
    }
}
