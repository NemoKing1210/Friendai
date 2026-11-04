package dev.friendai.agent.plan;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Drafts a plan for a goal. May fail; the caller then carries on without it. */
public interface Planner {
    CompletableFuture<PlanProposal> propose(PlannerInput input);

    /**
     * The same consultation, abandoned as soon as {@code cancelled} turns true: a turn that was stopped while the
     * planner worked sends no further request and delivers nothing. Implementations that cannot observe it keep the
     * plain behaviour.
     */
    default CompletableFuture<PlanProposal> propose(PlannerInput input, BooleanSupplier cancelled) {
        return propose(input);
    }
}
