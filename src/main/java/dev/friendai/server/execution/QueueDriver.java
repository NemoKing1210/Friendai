package dev.friendai.server.execution;

import dev.friendai.creative.CreativeActionContext;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.queue.FailurePolicy;
import dev.friendai.queue.GuardFacts;
import dev.friendai.queue.Job;
import dev.friendai.queue.JobQueue;
import dev.friendai.queue.JobState;
import dev.friendai.queue.PlanCursor;
import dev.friendai.queue.PlanDefinition;
import dev.friendai.queue.PlanInterpreter;
import dev.friendai.queue.PlanOptions;
import dev.friendai.queue.PlanRecord;
import dev.friendai.queue.PlanStatus;
import dev.friendai.queue.QueuePolicy;
import dev.friendai.queue.QueueSave;
import dev.friendai.queue.QueueText;
import dev.friendai.queue.QueuedStep;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.task.TaskContext;
import dev.friendai.task.plan.PlanStepFactory;
import dev.friendai.task.plan.StepFactory;
import dev.friendai.task.queue.CompanionGuardFacts;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Owns queued jobs, plan progression, guard results and task factories for one execution coordinator. */
public final class QueueDriver {
    private final ExecutionCoordinator coordinator;
    private JobQueue queue;
    private ServerSettings queueSettings = ServerSettings.DEFAULTS;
    private final Map<Long, StepFactory> factories = new HashMap<>();
    private final Map<Long, Map<String, PlanStepFactory>> structuredFactories = new HashMap<>();
    private final Map<Long, String> blockedPlans = new HashMap<>();
    private final Set<Long> awaitingDecision = new HashSet<>();
    private final Set<Long> autonomousPlans = new HashSet<>();
    private long blockedSince = -1;
    private long nextPoll;
    private CompanionGuardFacts sharedFacts;

    QueueDriver(ExecutionCoordinator coordinator) {
        this.coordinator = coordinator;
        this.queue = new JobQueue(queueSettings.queueLimits());
    }

    JobQueue queue() {
        return queue;
    }

    void advanceClock() {
        queue.advanceClock();
    }

    void markRunning(long id) {
        queue.markRunning(id);
    }

    JobQueue.FailureOutcome fail(long id, String reason) {
        return queue.fail(id, reason);
    }

    void finish(long id, String summary) {
        queue.finish(id, summary);
    }

    void remove(long id) {
        queue.remove(id);
        factories.remove(id);
    }

    void cancelJob(long id) {
        queue.cancel(id);
    }

    void cancelPlan(long id) {
        queue.cancelPlan(id);
    }

    void requeue(long id) {
        queue.requeue(id);
    }

    List<Long> expiredPlans() {
        return queue.expiredPlans();
    }

    void markAwaitingDecision(long id) {
        awaitingDecision.add(id);
    }

    void clearAwaitingDecision(long id) {
        awaitingDecision.remove(id);
    }

    void clearBlockedPlan(long id) {
        blockedPlans.remove(id);
    }

    void resetBlockedClock() {
        blockedSince = -1;
    }

    void deferNextPoll(long interval) {
        nextPoll = queue.clock() + interval;
    }

    boolean pollDue() {
        return queue.clock() >= nextPoll;
    }

    boolean isAutonomous(long planId) {
        return autonomousPlans.contains(planId);
    }

    long totalTravelledDistance() {
        return sharedFacts == null ? 0 : sharedFacts.totalTravelledDistance();
    }

    GuardFacts refreshFacts(CompanionEntity companion) {
        if (sharedFacts == null) sharedFacts = new CompanionGuardFacts(companion);
        sharedFacts.refresh();
        return sharedFacts;
    }

    GuardFacts refreshPlanFacts(CompanionEntity companion, PlanRecord plan) {
        if (sharedFacts == null) sharedFacts = new CompanionGuardFacts(companion);
        long baseline = plan.cursor() == null ? 0 : plan.cursor().startDistance();
        sharedFacts.refresh(
                plan.cursor() == null ? plan.addedTick() : plan.cursor().startTick(), baseline);
        return sharedFacts;
    }

    JobQueue.Result skipJob(long id) {
        Job job = queue.job(id).orElse(null);
        if (job == null) return JobQueue.Result.error("unknown job #" + id);
        JobQueue.Result result = queue.skip(id);
        if (!result.ok()) return result;
        if (job.planId() == 0) {
            remove(job.id());
        } else if (isStructuredPlan(job.planId())) {
            advanceStructuredPlan(job.planId(), new PlanInterpreter.ActionResult.Succeeded());
        } else if (queue.planStatus(job.planId()) == PlanStatus.DONE
                || queue.planStatus(job.planId()) == PlanStatus.FAILED) {
            finishPlan(
                    job.planId(),
                    QueueText.planSummary(queue, job.planId()),
                    queue.planStatus(job.planId()) == PlanStatus.DONE);
        }
        return result;
    }

    Job enqueue(QueuedStep step) {
        return queue.addDirect(step);
    }

    JobQueue.Added enqueuePlan(String goal, List<QueuedStep> steps, PlanOptions options) {
        return queue.addPlan(goal, steps, options);
    }

    long enqueueStructuredPlan(
            String goal, PlanDefinition definition, PlanOptions options, long startTick, long distance) {
        long id = queue.addStructuredPlan(goal, definition, options);
        queue.updatePlanCursor(id, PlanCursor.start(startTick, distance));
        return id;
    }

    JobQueue.Result clear() {
        factories.clear();
        structuredFactories.clear();
        awaitingDecision.clear();
        autonomousPlans.clear();
        blockedPlans.clear();
        resetAfterClear();
        return queue.clear();
    }

    void clearFactory(long id) {
        factories.remove(id);
    }

    void clearFactories() {
        factories.clear();
        structuredFactories.clear();
    }

    void clearPlanState() {
        blockedPlans.clear();
        awaitingDecision.clear();
    }

    void removeAwaitingDecision(long id) {
        awaitingDecision.remove(id);
    }

    void clearAwaitingDecisions() {
        awaitingDecision.clear();
    }

    void addStructuredFactories(long planId, Map<String, PlanStepFactory> planFactories) {
        structuredFactories.put(planId, Map.copyOf(planFactories));
    }

    void pruneFactories() {
        factories.keySet().removeIf(id -> queue.job(id).isEmpty());
    }

    void clearFactoryForRemovedJobs() {
        factories.keySet().removeIf(id -> queue.job(id).isEmpty());
    }

    void addFactory(long jobId, StepFactory factory) {
        factories.put(jobId, factory);
    }

    void markAutonomous(long planId) {
        autonomousPlans.add(planId);
    }

    void unmarkAutonomous(long planId) {
        autonomousPlans.remove(planId);
    }

    void resetAfterClear() {
        blockedSince = -1;
        nextPoll = 0;
    }

    boolean isAwaitingDecision() {
        awaitingDecision.removeIf(id -> queue.planStatus(id) != PlanStatus.NEEDS_DECISION);
        return !awaitingDecision.isEmpty();
    }

    void rebuildAwaitingDecisionState() {
        awaitingDecision.clear();
        for (PlanRecord plan : queue.plans())
            if (queue.planStatus(plan.id()) == PlanStatus.NEEDS_DECISION) awaitingDecision.add(plan.id());
    }

    void cancelPlanAndFinish(long id, String summary) {
        queue.cancelPlan(id);
        finishPlan(id, summary, false);
    }

    void applySettings(ServerSettings settings) {
        if (settings == null || queueSettings == settings) return;
        queue.setLimits(settings.queueLimits());
        queueSettings = settings;
    }

    void restore(
            QueueSave.Decoded decoded,
            Map<Long, StepFactory> restoredFactories,
            Map<Long, Map<String, PlanStepFactory>> restoredPlanFactories) {
        applySettings(coordinator.settings());
        queue = decoded.queue();
        queue.setLimits(queueSettings.queueLimits());
        factories.clear();
        factories.putAll(restoredFactories);
        structuredFactories.clear();
        restoredPlanFactories.forEach((id, map) -> structuredFactories.put(id, Map.copyOf(map)));
        awaitingDecision.clear();
        nextPoll = 0;
    }

    public Optional<Job> next(GuardFacts facts) {
        return QueuePolicy.next(queue, facts);
    }

    Optional<Job> nextReady() {
        skipUnmet(coordinator.facts());
        Optional<Job> candidate = next(coordinator.facts());
        if (candidate.isEmpty() && advanceNextStructuredPlan()) candidate = next(coordinator.facts());
        return candidate;
    }

    public Job nextGuardSkip(GuardFacts facts) {
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

    public StepFactory factory(long jobId) {
        return factories.get(jobId);
    }

    public PlanStepFactory planFactory(long planId, String tool) {
        return structuredFactories.getOrDefault(planId, Map.of()).get(tool);
    }

    public Optional<PlanRecord> nextStructuredPlan() {
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

    boolean isStructuredPlan(long planId) {
        return queue.plan(planId)
                .map(plan -> plan.definition() != null && plan.cursor() != null)
                .orElse(false);
    }

    boolean advanceNextStructuredPlan() {
        PlanRecord selected = nextStructuredPlan().orElse(null);
        if (selected == null) return false;
        advanceStructuredPlan(selected.id(), new PlanInterpreter.ActionResult.None());
        return true;
    }

    void advanceStructuredPlan(long planId, PlanInterpreter.ActionResult result) {
        PlanRecord plan = queue.plan(planId).orElse(null);
        if (plan == null || plan.cursor() == null) return;
        var transition = advance(plan.definition(), plan.cursor(), coordinator.planFacts(plan), result);
        queue.updatePlanCursor(planId, transition.cursor());
        if (transition.event() instanceof PlanInterpreter.Event.EmitAction emit) {
            QueuedStep action = emit.step();
            PlanStepFactory factory = planFactory(planId, action.tool());
            if (factory == null) {
                queue.cancelPlan(planId);
                finishPlan(planId, "task factory is unavailable for " + action.tool() + " at " + emit.path(), false);
                return;
            }
            CreativeActionContext actionContext =
                    coordinator.actionContext(action.principal(), action.creativeSensitive());
            TaskContext taskContext = coordinator.queueTaskContext(actionContext);
            if (!factory.authorized(taskContext, action)) {
                queue.cancelPlan(planId);
                finishPlan(planId, "access to " + action.tool() + " was revoked at " + emit.path(), false);
                return;
            }
            try {
                coordinator.requireCreativeAuthorization(action.principal(), action.creativeSensitive());
                QueuedStep emitted = new QueuedStep(
                        action.tool(),
                        action.args(),
                        action.priority(),
                        action.guards(),
                        action.onUnmet(),
                        action.onFailure(),
                        action.maxAttempts(),
                        List.of(),
                        action.principal(),
                        action.creativeSensitive());
                Job job = queue.addJob(planId, emitted);
                queue.updatePlanCursor(
                        planId, queue.plan(planId).orElseThrow().cursor().withPendingJob(job.id()));
                factories.put(job.id(), context -> factory.create(context, emitted));
            } catch (RuntimeException exception) {
                queue.cancelPlan(planId);
                finishPlan(planId, "could not emit action at " + emit.path() + ": " + exception.getMessage(), false);
            }
        } else if (transition.event() instanceof PlanInterpreter.Event.Complete) {
            finishPlan(planId, "conditional plan completed", true);
        } else if (transition.event() instanceof PlanInterpreter.Event.Failed failed) {
            queue.cancelPlan(planId);
            finishPlan(planId, failed.path() + ": " + failed.reason(), false);
        }
    }

    void skipUnmet(GuardFacts facts) {
        Job job;
        while ((job = nextGuardSkip(facts)) != null) {
            queue.skip(job.id());
            if (isStructuredPlan(job.planId()))
                advanceStructuredPlan(job.planId(), new PlanInterpreter.ActionResult.Succeeded());
            else if (job.planId() > 0 && queue.planStatus(job.planId()) != PlanStatus.ACTIVE)
                finishPlan(
                        job.planId(),
                        QueueText.planSummary(queue, job.planId()),
                        queue.planStatus(job.planId()) == PlanStatus.DONE);
        }
    }

    void handleStructuredActionFailure(long planId, long jobId, String reason) {
        PlanRecord plan = queue.plan(planId).orElse(null);
        if (plan == null) return;
        FailurePolicy policy = queue.job(jobId).map(Job::onFailure).orElse(plan.onFailure());
        switch (policy) {
            case SKIP -> advanceStructuredPlan(planId, new PlanInterpreter.ActionResult.Succeeded());
            case STOP_PLAN -> {
                queue.cancelPlan(planId);
                finishPlan(planId, reason, false);
            }
            case ASK -> {
                awaitingDecision.add(planId);
                notifyBlocked(planId, jobId, reason);
            }
        }
    }

    void notifyBlocked(long planId, long jobId, String reason) {
        Job job = queue.job(jobId).orElse(null);
        if (job == null) return;
        String goal = queue.plan(planId).map(PlanRecord::goal).orElse("");
        int remaining = (int) queue.jobsOfPlan(planId).stream()
                .filter(candidate -> candidate.state() == JobState.PENDING)
                .count();
        String bounded = QueueText.truncate(reason, QueueText.MAX_BLOCKED_REASON);
        blockedPlans.put(planId, bounded);
        coordinator.publishPlanBlocked(planId, goal, jobId, job.tool(), bounded, remaining);
    }

    void finishPlan(long id, String summary, boolean success) {
        PlanRecord plan = queue.plan(id).orElse(null);
        if (plan == null) return;
        String goal = plan.goal();
        queue.archivePlan(id, success, summary);
        factories.entrySet().removeIf(entry -> queue.job(entry.getKey()).isEmpty());
        structuredFactories.remove(id);
        blockedPlans.remove(id);
        awaitingDecision.remove(id);
        coordinator.publishPlanFinished(id, goal, success, summary);
    }

    void maybeNoticeBlocked(GuardFacts facts, long noticeInterval, boolean activeJob) {
        if (activeJob) return;
        if (blockedSince < 0) blockedSince = queue.clock();
        if (queue.clock() - blockedSince < noticeInterval) return;
        for (PlanRecord plan : queue.plans()) {
            if (queue.planStatus(plan.id()) != PlanStatus.ACTIVE) continue;
            Job gating = queue.jobsOfPlan(plan.id()).stream()
                    .filter(j -> j.state() == JobState.PENDING
                            && j.retryAtTick() <= queue.clock()
                            && QueuePolicy.dependenciesDone(queue, j))
                    .findFirst()
                    .orElse(null);
            if (gating == null) continue;
            if (QueuePolicy.ready(queue, gating, facts)) {
                blockedPlans.remove(plan.id());
                continue;
            }
            String reason =
                    QueueText.truncate(QueuePolicy.notReadyReason(queue, gating, facts), QueueText.MAX_BLOCKED_REASON);
            if (!reason.equals(blockedPlans.get(plan.id()))) notifyBlocked(plan.id(), gating.id(), reason);
        }
    }

    static QueuedStep step(Job job) {
        return new QueuedStep(
                job.tool(),
                job.args(),
                job.priorityExplicit() ? job.priority() : null,
                job.guards(),
                job.onUnmet(),
                job.onFailure(),
                job.maxAttempts(),
                List.of(),
                job.principal(),
                job.creativeSensitive());
    }
}
