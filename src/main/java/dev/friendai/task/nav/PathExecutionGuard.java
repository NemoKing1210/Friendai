package dev.friendai.task.nav;

import dev.friendai.features.navigation.pathing.PathStep;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.task.TaskContext;

/** Live execution checks applied immediately before a path break or move. */
public interface PathExecutionGuard {

    PathExecutionGuard DEFAULT = new PathExecutionGuard() {
        @Override
        public boolean allowBreak(TaskContext context, PathStep step, Pos pos) {
            return true;
        }

        @Override
        public boolean allowMove(TaskContext context, PathStep step) {
            return true;
        }
    };

    boolean allowBreak(TaskContext context, PathStep step, Pos pos);

    boolean allowMove(TaskContext context, PathStep step);
}
