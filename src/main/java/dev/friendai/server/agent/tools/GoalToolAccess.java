package dev.friendai.server.agent.tools;

import dev.friendai.agent.goal.GoalBoard;
import dev.friendai.agent.goal.GoalService;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** Server capabilities used by persistent goal tools. */
public interface GoalToolAccess {
    GoalService goals(ServerLevel level);

    void goalOutcome(ServerLevel level, UUID owner, GoalBoard.Finished outcome);
}
