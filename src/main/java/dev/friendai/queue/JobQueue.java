package dev.friendai.queue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** Mutable owner of immutable queue records; deliberately independent of Minecraft. */
public final class JobQueue {
    public record Added(long planId, List<Long> jobIds) { public Added { jobIds = List.copyOf(jobIds); } }
    public record Result(boolean ok, String text) { public static Result ok(String text) { return new Result(true, text); } public static Result error(String text) { return new Result(false, text); } }
    public record FailureOutcome(Job job, boolean retried) {}

    private final QueueLimits limits;
    private final LinkedHashMap<Long, Job> jobs = new LinkedHashMap<>();
    private final LinkedHashMap<Long, PlanRecord> plans = new LinkedHashMap<>();
    private final List<PlanArchive> archives = new ArrayList<>();
    private long nextJob = 1, nextPlan = 1, nextOrder = 100, clock;

    public JobQueue(QueueLimits limits) { this.limits = limits == null ? QueueLimits.DEFAULTS : limits; }
    public QueueLimits limits() { return limits; }
    public long clock() { return clock; }
    public void advanceClock() { clock++; }
    public int jobCount() { return jobs.size(); }
    public int planCount() { return plans.size(); }
    public List<Job> jobs() { return List.copyOf(jobs.values()); }
    public List<Job> jobsOfPlan(long id) { return jobs.values().stream().filter(j -> j.planId() == id).toList(); }
    public List<PlanRecord> plans() { return List.copyOf(plans.values()); }
    public List<PlanArchive> archives() { return List.copyOf(archives); }
    public Optional<Job> job(long id) { return Optional.ofNullable(jobs.get(id)); }
    public Optional<PlanRecord> plan(long id) { return Optional.ofNullable(plans.get(id)); }
    public Optional<Job> active() { return jobs.values().stream().filter(j -> j.state() == JobState.RUNNING).findFirst(); }
    public PlanStatus planStatus(long id) { return QueuePolicy.planStatus(this, id); }

    public Added addPlan(String goal, List<QueuedStep> steps, PlanOptions options) {
        if (steps == null || steps.isEmpty() || steps.size() > limits.maxStepsPerAdd()) throw new IllegalArgumentException("invalid step count");
        if (plans.size() >= limits.maxPlans()) throw new IllegalArgumentException("queue has too many plans");
        ensureJobCapacity(steps.size());
        options = options == null ? PlanOptions.DEFAULT : options;
        validateBudget(steps);
        for (int i = 0; i < steps.size(); i++) {
            QueuedStep step = steps.get(i);
            if (step.tool().isBlank()) throw new IllegalArgumentException("tool is blank");
            for (int after : step.afterSteps()) if (after < 1 || after > i) throw new IllegalArgumentException("after may only name an earlier step");
        }
        long planId = nextPlan++;
        plans.put(planId, new PlanRecord(planId, goal, options.priority(), clock,
                options.timeLimitTicks() == 0 ? 0 : clock + options.timeLimitTicks(), options.onFailure()));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) ids.add(nextJob++);
        for (int i = 0; i < steps.size(); i++) putNew(ids.get(i), planId, steps.get(i), options, ids);
        return new Added(planId, ids);
    }

    public Job addDirect(QueuedStep step) {
        if (step == null || !step.afterSteps().isEmpty()) throw new IllegalArgumentException("direct jobs cannot have dependencies");
        ensureJobCapacity(1); validateBudget(List.of(step));
        long id = nextJob++;
        Job job = new Job(id, 0, step.tool(), step.args(), step.priority() == null ? 0 : step.priority(), step.priority() != null,
                nextOrder, JobState.PENDING, step.guards(), step.onUnmet(), step.onFailure() == null ? FailurePolicy.STOP_PLAN : step.onFailure(),
                step.maxAttempts(), 0, List.of(), "", 0);
        nextOrder += 100; jobs.put(id, job); return job;
    }

    public Job addJob(long planId, QueuedStep step) {
        PlanRecord plan = plans.get(planId);
        if (plan == null || !step.afterSteps().isEmpty()) throw new IllegalArgumentException("unknown plan or dependencies");
        ensureJobCapacity(1); validateBudget(List.of(step));
        long id = nextJob++;
        Job job = new Job(id, planId, step.tool(), step.args(), step.priority() == null ? plan.priority() : step.priority(), step.priority() != null,
                nextOrder, JobState.PENDING, step.guards(), step.onUnmet(), step.onFailure() == null ? plan.onFailure() : step.onFailure(),
                step.maxAttempts(), 0, List.of(), "", 0);
        nextOrder += 100; jobs.put(id, job); return job;
    }

    private void putNew(long id, long planId, QueuedStep step, PlanOptions options, List<Long> ids) {
        List<Long> after = step.afterSteps().stream().map(index -> ids.get(index - 1)).toList();
        Job job = new Job(id, planId, step.tool(), step.args(), step.priority() == null ? options.priority() : step.priority(),
                step.priority() != null, nextOrder, JobState.PENDING, step.guards(), step.onUnmet(),
                step.onFailure() == null ? options.onFailure() : step.onFailure(), step.maxAttempts(), 0, after, "", 0);
        nextOrder += 100; jobs.put(id, job);
    }
    private void ensureJobCapacity(int add) { if (jobs.size() + add > limits.maxJobs()) throw new IllegalArgumentException("queue has too many jobs"); }
    private void validateBudget(List<QueuedStep> add) {
        int total = jobs.values().stream().mapToInt(j -> j.args().toString().length()).sum();
        for (QueuedStep s : add) { int size = s.args().toString().length(); if (size > limits.maxArgsChars()) throw new IllegalArgumentException("arguments are too large"); total += size; }
        if (total > limits.maxQueueArgsChars()) throw new IllegalArgumentException("queue arguments are too large");
    }
    private void replace(Job old, JobState state, String result, int attempts, long retryAt) {
        jobs.put(old.id(), new Job(old.id(), old.planId(), old.tool(), old.args(), old.priority(), old.priorityExplicit(), old.order(), state,
                old.guards(), old.onUnmet(), old.onFailure(), old.maxAttempts(), attempts, old.after(), result, retryAt));
    }
    public void markRunning(long id) { Job job = require(id); replace(job, JobState.RUNNING, job.result(), job.attempts(), job.retryAtTick()); }
    public void finish(long id, String summary) { Job job = require(id); replace(job, JobState.DONE, summary, job.attempts(), 0); }
    public FailureOutcome fail(long id, String reason) {
        Job job = require(id); int attempts = job.attempts() + 1;
        if (attempts < job.maxAttempts()) { replace(job, JobState.PENDING, reason, attempts, clock + 20L * attempts); return new FailureOutcome(jobs.get(id), true); }
        JobState state = job.onFailure() == FailurePolicy.SKIP ? JobState.SKIPPED : JobState.FAILED;
        replace(job, state, reason, attempts, 0);
        if (job.onFailure() == FailurePolicy.STOP_PLAN && job.planId() != 0) cancelPlan(job.planId());
        return new FailureOutcome(jobs.get(id), false);
    }
    public Result cancel(long id) { Job job = jobs.get(id); if (job == null) return Result.error("unknown job #" + id); replace(job, JobState.CANCELLED, "cancelled", job.attempts(), 0); return Result.ok("cancelled #" + id); }
    public Result cancelPlan(long planId) { if (!plans.containsKey(planId)) return Result.error("unknown plan #" + planId); for (Job j : jobsOfPlan(planId)) if (!terminal(j)) replace(j, JobState.CANCELLED, "cancelled", j.attempts(), 0); return Result.ok("cancelled plan #" + planId); }
    public Result remove(long id) { return jobs.remove(id) == null ? Result.error("unknown job #" + id) : Result.ok("removed #" + id); }
    public Result clear() { int pc = plans.size(), jc = jobs.size(); plans.clear(); jobs.clear(); return Result.ok("cleared " + pc + " plans and " + jc + " jobs"); }
    public Result retry(long id) { Job j = jobs.get(id); if (j == null || j.state() != JobState.FAILED) return Result.error("job cannot retry"); replace(j, JobState.PENDING, "", 0, 0); return Result.ok("#" + id + " will retry"); }
    public Result skip(long id) { Job j = jobs.get(id); if (j == null) return Result.error("unknown job #" + id); replace(j, JobState.SKIPPED, "skipped", j.attempts(), 0); return Result.ok("skipped #" + id); }
    public List<Long> expiredPlans() { return plans.values().stream().filter(p -> p.deadlineTick() > 0 && p.deadlineTick() <= clock).map(PlanRecord::id).toList(); }
    public void archivePlan(long id) { PlanRecord p = plans.remove(id); if (p == null) return; List<Job> planJobs = jobsOfPlan(id); boolean success = planJobs.stream().anyMatch(j -> j.state() == JobState.DONE); archives.add(new PlanArchive(id, p.goal(), success, planJobs.size(), success ? "finished" : "failed", clock)); planJobs.forEach(j -> jobs.remove(j.id())); while (archives.size() > limits.maxArchives()) archives.removeFirst(); }
    public static boolean terminal(Job j) { return switch (j.state()) { case DONE, FAILED, SKIPPED, CANCELLED -> true; default -> false; }; }
    private Job require(long id) { Job j = jobs.get(id); if (j == null) throw new IllegalArgumentException("unknown job #" + id); return j; }
}
