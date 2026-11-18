package dev.friendai.server.agent;

import dev.friendai.agent.plan.PlanSubject;
import dev.friendai.agent.plan.PlannerInput;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.Caller;
import java.util.Set;

/** Reads the planner's actual caller view without submitting a model request. */
public final class ToolCatalogTestAccess {
    private ToolCatalogTestAccess() {}

    public static Set<String> plannerTools(CompanionSessions sessions, CompanionEntity companion, Caller caller) {
        return plannerInput(sessions, companion, caller).allTools();
    }

    public static PlannerInput plannerInput(CompanionSessions sessions, CompanionEntity companion, Caller caller) {
        return sessions.plannerInputs(companion.ownerUuid(), companion)
                .build(new PlanSubject.OwnerOrder("Walk to a point"), caller, "")
                .orElseThrow();
    }
}
