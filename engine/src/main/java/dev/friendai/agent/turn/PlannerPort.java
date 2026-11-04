package dev.friendai.agent.turn;

import dev.friendai.agent.plan.PlanProposal;
import dev.friendai.agent.plan.PlanSubject;
import dev.friendai.agent.plan.PlannerInput;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * What the brain needs to consult the planner: build the input for one subject from the world, ask, and queue the
 * answer. The methods that touch the world run on the brain's scheduler thread; {@link #propose} may complete on any
 * thread.
 */
public interface PlannerPort {
    PlannerPort NONE = new PlannerPort() {
        @Override
        public Optional<PlannerInput> inputFor(PlanSubject subject, Caller caller, String failure) {
            return Optional.empty();
        }

        @Override
        public CompletableFuture<PlanProposal> propose(PlannerInput input) {
            return CompletableFuture.failedFuture(new IllegalStateException("no planner"));
        }

        @Override
        public ToolResult apply(Caller caller, PlanSubject subject, PlanProposal proposal) {
            return ToolResult.error("no planner");
        }
    };

    /** Whether this queue plan is the one the owner's primary goal follows; other plans' failures are not its business. */
    default boolean ownsPlan(long planId) {
        return true;
    }

    /**
     * The planner input for one subject, or empty when its prerequisite is gone: no goal, no queued plan, or a shift
     * that already ended.
     */
    Optional<PlannerInput> inputFor(PlanSubject subject, Caller caller, String failure);

    CompletableFuture<PlanProposal> propose(PlannerInput input);

    /**
     * The same consultation, told when the turn it serves has been stopped so it neither retries nor delivers. Ports
     * that cannot observe the turn keep the plain behaviour.
     */
    default CompletableFuture<PlanProposal> propose(PlannerInput input, BooleanSupplier cancelled) {
        return propose(input);
    }

    /**
     * Queues the proposal with the caller's access. A subject that targets a queued plan repairs that plan; every
     * other subject enqueues a new plan and never touches the goal's own. Never throws for a bad plan: the result
     * carries the error.
     */
    ToolResult apply(Caller caller, PlanSubject subject, PlanProposal proposal);
}
