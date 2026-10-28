package dev.friendai.server.execution;

import dev.friendai.queue.GuardFacts;
import dev.friendai.queue.Job;
import dev.friendai.queue.JobQueue;
import dev.friendai.queue.PlanCursor;
import dev.friendai.queue.PlanDefinition;
import dev.friendai.queue.PlanInterpreter;
import dev.friendai.queue.PlanRecord;
import dev.friendai.queue.QueuePolicy;
import dev.friendai.task.plan.PlanStepFactory;
import dev.friendai.task.plan.StepFactory;
import java.util.Map;
import java.util.Optional;

/** Bounded queue selection policy; lifecycle changes remain owned by the coordinator and active-task controller. */
public final class QueueDriver {
    public Optional<Job> next(JobQueue queue, GuardFacts facts) {
        return QueuePolicy.next(queue, facts);
    }

    public Job nextGuardSkip(JobQueue queue, GuardFacts facts) {
        for (Job job : queue.jobs()) {
            if (job.state() != dev.friendai.queue.JobState.PENDING
                    || job.onUnmet() != dev.friendai.queue.GuardAction.SKIP) continue;
            for (var guard : job.guards()) if (!guard.holds(facts)) return job;
        }
        return null;
    }

    public PlanInterpreter.Transition advance(
            PlanDefinition definition, PlanCursor cursor, GuardFacts facts, PlanInterpreter.ActionResult result) {
        return PlanInterpreter.advance(definition, cursor, facts, result);
    }

    public StepFactory factory(Map<Long, StepFactory> factories, long jobId) {
        return factories.get(jobId);
    }

    public PlanStepFactory planFactory(Map<Long, Map<String, PlanStepFactory>> factories, long planId, String tool) {
        return factories.getOrDefault(planId, Map.of()).get(tool);
    }

    public Optional<PlanRecord> nextStructuredPlan(JobQueue queue) {
        PlanRecord selected = null;
        for (PlanRecord plan : queue.plans()) {
            if (plan.cursor() == null
                    || !plan.cursor().pendingActionPath().isEmpty()
                    || plan.cursor().stack().isEmpty()) continue;
            if (selected == null
                    || plan.priority() > selected.priority()
                    || plan.priority() == selected.priority() && plan.addedTick() < selected.addedTick()) {
                selected = plan;
            }
        }
        return Optional.ofNullable(selected);
    }
}
