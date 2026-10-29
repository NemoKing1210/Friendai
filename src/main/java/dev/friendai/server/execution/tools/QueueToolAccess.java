package dev.friendai.server.execution.tools;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.Caller;
import java.util.UUID;

/** Server capabilities needed to recheck queued-task authority and link queued plans to owner goals. */
public interface QueueToolAccess {
    Caller currentCaller(CompanionEntity companion, UUID principal);

    void linkPlan(CompanionEntity companion, long planId);
}
