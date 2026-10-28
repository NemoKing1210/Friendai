package dev.friendai.tree;

import java.util.ArrayList;
import java.util.List;

/**
 * The felling order for one tree, computed without a world. A climb is one scaffold column beside the trunk with the
 * layers cut top-down from it; the alternatives are ranked so the executor can try the next side when the world
 * blocks the first one. The companion is never moved by the plan: it climbs once, and the stance only descends.
 */
public record FellPlan(List<Climb> climbs, List<TreeCell> cutOrder, int totalLogs, int unreachableLogs) {
    public FellPlan {
        climbs = List.copyOf(climbs);
        cutOrder = List.copyOf(cutOrder);
    }

    /** True when a limit holds the cut back from the logs it could otherwise reach. */
    public boolean truncated() {
        return cutOrder.size() + unreachableLogs < totalLogs;
    }

    /** One scaffold column and the stages cut from it, in execution order. */
    public record Climb(TreeCell column, int groundFeetY, List<Stage> stages) {
        public Climb {
            stages = List.copyOf(stages);
        }

        /** The feet height the climb ends at: the first stage's stance, or the ground when nothing is cut. */
        public int climbFeetY() {
            return stages.isEmpty() ? groundFeetY : stages.get(0).feetY();
        }

        /** How many scaffold blocks the climb costs above the ground. */
        public int scaffoldNeeded() {
            return Math.max(0, climbFeetY() - groundFeetY);
        }

        /** Every log this climb cuts, in order. */
        public List<TreeCell> logs() {
            List<TreeCell> logs = new ArrayList<>();
            for (Stage stage : stages) {
                logs.addAll(stage.logs());
            }
            return List.copyOf(logs);
        }
    }

    /** The logs cut from one stance, highest first as they were ordered. */
    public record Stage(int feetY, List<TreeCell> logs) {
        public Stage {
            logs = List.copyOf(logs);
        }
    }
}
