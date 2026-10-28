package dev.friendai.agent.plan;

import java.util.concurrent.CompletableFuture;

/** Drafts a plan for a goal. May fail; the caller then carries on without it. */
public interface Planner {
    CompletableFuture<PlanProposal> propose(PlannerInput input);
}
