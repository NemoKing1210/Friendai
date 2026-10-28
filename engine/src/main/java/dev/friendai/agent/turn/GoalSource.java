package dev.friendai.agent.turn;

import dev.friendai.agent.goal.GoalView;
import dev.friendai.execution.tool.Caller;

/** What the brain may show of the owner's primary goal in the prompt. */
@FunctionalInterface
public interface GoalSource {
    GoalSource NONE = caller -> GoalView.EMPTY;

    GoalView view(Caller caller);
}
