package dev.friendai.server.agent.tools;

import dev.friendai.agent.goal.GoalService;
import dev.friendai.foundation.teaching.TeachingContext;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** Server capabilities used by persistent goal tools. */
public interface GoalToolAccess {
    GoalService goals(ServerLevel level);

    void skillOutcome(ServerLevel level, UUID owner, String source, TeachingContext.OutcomeKind kind, String summary);
}
