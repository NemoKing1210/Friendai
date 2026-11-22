package dev.friendai.server.execution.tools;

import dev.friendai.agent.goal.Goal;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.Caller;
import java.util.Optional;
import java.util.UUID;

/** Server capabilities needed to recheck queued-task authority and link queued plans to owner goals. */
public interface QueueToolAccess {
    Caller currentCaller(CompanionEntity companion, UUID principal);

    /** Selects the explicit owner goal or the goal created in this same owner turn. */
    default Optional<Goal> goalForPlan(CompanionEntity companion, Caller caller, Long goalId) {
        if (goalId != null) throw new IllegalArgumentException("goal linking is unavailable");
        return Optional.empty();
    }

    /** Attaches the previously selected, still-current goal before the plan starts. */
    default void linkPlan(CompanionEntity companion, long planId, Goal goal) {
        throw new IllegalArgumentException("goal linking is unavailable");
    }
}
