package dev.friendai.brain;

import dev.friendai.agent.plan.PlanProposal;
import dev.friendai.agent.plan.PlannerInput;
import dev.friendai.tool.Caller;
import dev.friendai.tool.ToolResult;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * What the brain needs to consult the planner: build the input from the world, ask, and queue the answer. The
 * methods that touch the world run on the brain's scheduler thread; {@link #propose} may complete on any thread.
 */
public interface PlannerPort {
    PlannerPort NONE = new PlannerPort() {
        @Override
        public Optional<PlannerInput> input(Caller caller, String failure) {
            return Optional.empty();
        }

        @Override
        public CompletableFuture<PlanProposal> propose(PlannerInput input) {
            return CompletableFuture.failedFuture(new IllegalStateException("no planner"));
        }

        @Override
        public ToolResult apply(Caller caller, PlanProposal proposal) {
            return ToolResult.error("no planner");
        }
    };

    /** Whether this queue plan is the one the owner's primary goal follows; other plans' failures are not its business. */
    default boolean ownsPlan(long planId) {
        return true;
    }

    /** The planner input for the owner's primary goal, or empty when there is no goal or no planner. */
    Optional<PlannerInput> input(Caller caller, String failure);

    CompletableFuture<PlanProposal> propose(PlannerInput input);

    /**
     * Queues the proposal with the caller's access, replacing the goal's current plan. Never throws for a bad
     * plan: the result carries the error.
     */
    ToolResult apply(Caller caller, PlanProposal proposal);
}
