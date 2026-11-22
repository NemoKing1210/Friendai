package dev.friendai.server.agent.tools;

import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalBoard;
import dev.friendai.agent.goal.GoalService;
import dev.friendai.entity.CompanionEntity;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** Server capabilities used by persistent goal tools. */
public interface GoalToolAccess {
    GoalService goals(ServerLevel level);

    default void goalCreated(CompanionEntity companion, Goal goal) {}

    default Optional<String> rejectExistingPlan(CompanionEntity companion, UUID owner, long planId, String objective) {
        return Optional.of("the queued plan cannot be verified");
    }

    void goalOutcome(ServerLevel level, UUID owner, GoalBoard.Finished outcome);
}
