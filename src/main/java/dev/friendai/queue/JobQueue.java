package dev.friendai.queue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** Mutable owner of immutable queue records; deliberately independent of Minecraft. */
public final class JobQueue {
    public record Added(long planId, List<Long> jobIds) {
        public Added {
            jobIds = List.copyOf(jobIds);
        }
    }

    public record Result(boolean ok, String text) {
        public static Result ok(String text) {
            return new Result(true, text);
        }

        public static Result error(String text) {
            return new Result(false, text);
        }
    }

    public record FailureOutcome(Job job, boolean retried) {}

    private QueueLimits limits;
    private final LinkedHashMap<Long, Job> jobs = new LinkedHashMap<>();
    private final LinkedHashMap<Long, PlanRecord> plans = new LinkedHashMap<>();
    private final List<PlanArchive> archives = new ArrayList<>();
    private static final int MAX_STORED_RESULT = 300;
    private long nextJob = 1, nextPlan = 1, nextOrder = 100, clock;

    public JobQueue(QueueLimits limits) {
        this.limits = limits == null ? QueueLimits.DEFAULTS : limits;
    }

    static JobQueue restore(
            QueueLimits limits,
            long clock,
            long nextJob,
            long nextPlan,
            long nextOrder,
            List<PlanRecord> savedPlans,
            List<Job> savedJobs,
            List<PlanArchive> savedArchives) {
        JobQueue queue = new JobQueue(limits);
        queue.clock = Math.max(0, clock);
        for (PlanRecord plan : savedPlans) queue.plans.put(plan.id(), plan);
        for (Job job : savedJobs) queue.jobs.put(job.id(), job);
        queue.archives.addAll(savedArchives);
        queue.nextJob = Math.max(
                nextJob,
                queue.jobs.keySet().stream().mapToLong(Long::longValue).max().orElse(0) + 1);
        queue.nextPlan = Math.max(
                nextPlan,
                queue.plans.keySet().stream().mapToLong(Long::longValue).max().orElse(0) + 1);
        queue.nextOrder = Math.max(
                nextOrder,
                queue.jobs.values().stream().mapToLong(Job::order).max().orElse(0) + 100);
        while (queue.archives.size() > queue.limits.maxArchives()) queue.archives.removeFirst();
        return queue;
    }

    public long nextJobId() {
        return nextJob;
    }

    long nextPlanId() {
        return nextPlan;
    }

    long nextOrder() {
        return nextOrder;
    }

    public QueueLimits limits() {
        return limits;
    }

    /** Applies current runtime limits without discarding queued work. */
    public void setLimits(QueueLimits limits) {
        this.limits = limits == null ? QueueLimits.DEFAULTS : limits;
        while (archives.size() > this.limits.maxArchives()) archives.removeFirst();
    }

    public long clock() {
        return clock;
    }

    public void advanceClock() {
        clock++;
    }

    public int jobCount() {
        return jobs.size();
    }

    public int planCount() {
        return plans.size();
    }

    public List<Job> jobs() {
        return List.copyOf(jobs.values());
    }

    public List<Job> jobsOfPlan(long id) {
        return jobs.values().stream().filter(j -> j.planId() == id).toList();
    }

    public List<PlanRecord> plans() {
        return List.copyOf(plans.values());
    }

    public List<PlanArchive> archives() {
        return List.copyOf(archives);
    }

    public Optional<Job> job(long id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public Optional<PlanRecord> plan(long id) {
        return Optional.ofNullable(plans.get(id));
    }

    public Optional<Job> active() {
        return jobs.values().stream().filter(j -> j.state() == JobState.RUNNING).findFirst();
    }

    public PlanStatus planStatus(long id) {
        return QueuePolicy.planStatus(this, id);
    }

    public Added addPlan(String goal, List<QueuedStep> steps, PlanOptions options) {
        if (steps == null || steps.isEmpty() || steps.size() > limits.maxStepsPerAdd())
            throw new IllegalArgumentException("invalid step count");
        if (plans.size() >= limits.maxPlans()) throw new IllegalArgumentException("queue has too many plans");
        ensureJobCapacity(steps.size());
        options = options == null ? PlanOptions.DEFAULT : options;
        validateBudget(steps);
        for (int i = 0; i < steps.size(); i++) {
            QueuedStep step = steps.get(i);
            if (step.tool().isBlank()) throw new IllegalArgumentException("tool is blank");
            for (int after : step.afterSteps())
                if (after < 1 || after > i) throw new IllegalArgumentException("after may only name an earlier step");
        }
        long planId = nextPlan++;
        List<PlanNode> nodes = steps.stream()
                .map(PlanNode.Action::new)
                .map(PlanNode.class::cast)
                .toList();
        PlanDefinition definition = new PlanDefinition(nodes);
        plans.put(
                planId,
                new PlanRecord(
                        planId,
                        goal,
                        options.priority(),
                        clock,
                        options.timeLimitTicks() == 0 ? 0 : clock + options.timeLimitTicks(),
                        options.onFailure(),
                        definition,
                        null));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) ids.add(nextJob++);
        for (int i = 0; i < steps.size(); i++) putNew(ids.get(i), planId, steps.get(i), options, ids);
        return new Added(planId, ids);
    }

    /** Stores a tree without allocating jobs for actions in unselected branches or loop iterations. */
    public long addStructuredPlan(PlanDefinition definition, PlanOptions options) {
        return addStructuredPlan("", definition, options);
    }

    public long addStructuredPlan(String goal, PlanDefinition definition, PlanOptions options) {
        if (definition == null
                || definition.nodes().isEmpty()
                || definition.worstCaseActions() > limits.maxStepsPerAdd())
            throw new IllegalArgumentException("invalid structured plan");
        if (plans.size() >= limits.maxPlans()) throw new IllegalArgumentException("queue has too many plans");
        options = options == null ? PlanOptions.DEFAULT : options;
        List<QueuedStep> worstCase = new ArrayList<>();
        collectWorstCase(definition.nodes(), worstCase);
        if (worstCase.size() > limits.maxStepsPerAdd()) throw new IllegalArgumentException("invalid structured plan");
        validateBudget(worstCase);
        for (QueuedStep step : worstCase)
            if (step.tool().isBlank()) throw new IllegalArgumentException("tool is blank");
        long planId = nextPlan++;
        plans.put(
                planId,
                new PlanRecord(
                        planId,
                        goal,
                        options.priority(),
                        clock,
                        options.timeLimitTicks() == 0 ? 0 : clock + options.timeLimitTicks(),
                        options.onFailure(),
                        definition,
                        PlanCursor.start(clock, 0)));
        return planId;
    }

    private static void collectWorstCase(List<PlanNode> nodes, List<QueuedStep> actions) {
        for (PlanNode node : nodes) {
            if (node instanceof PlanNode.Action action) actions.add(action.step());
            else if (node instanceof PlanNode.Conditional conditional) {
                List<QueuedStep> thenSteps = new ArrayList<>(), elseSteps = new ArrayList<>();
                collectWorstCase(conditional.thenNodes(), thenSteps);
                collectWorstCase(conditional.elseNodes(), elseSteps);
                actions.addAll(thenSteps.size() >= elseSteps.size() ? thenSteps : elseSteps);
            } else {
                PlanNode.Repeat repeat = (PlanNode.Repeat) node;
                List<QueuedStep> body = new ArrayList<>();
                collectWorstCase(repeat.body(), body);
                for (int i = 0; i < repeat.maxIterations(); i++) actions.addAll(body);
            }
        }
    }

    public Job addDirect(QueuedStep step) {
        if (step == null || !step.afterSteps().isEmpty())
            throw new IllegalArgumentException("direct jobs cannot have dependencies");
        ensureJobCapacity(1);
        validateBudget(List.of(step));
        long id = nextJob++;
        Job job = new Job(
                id,
                0,
                step.tool(),
                step.args(),
                step.priority() == null ? 0 : step.priority(),
                step.priority() != null,
                nextOrder,
                JobState.PENDING,
                step.guards(),
                step.onUnmet(),
                step.onFailure() == null ? FailurePolicy.STOP_PLAN : step.onFailure(),
                step.maxAttempts(),
                0,
                List.of(),
                "",
                0,
                step.principal(),
                step.creativeSensitive());
        nextOrder += 100;
        jobs.put(id, job);
        return job;
    }

    public Job addJob(long planId, QueuedStep step) {
        PlanRecord plan = plans.get(planId);
        if (plan == null
                || plan.cursor() != null
                        && plan.cursor().stack().isEmpty()
                        && plan.cursor().pendingActionPath().isEmpty()
                || !step.afterSteps().isEmpty()) {
            throw new IllegalArgumentException("unknown or closed plan, or dependencies");
        }
        ensureJobCapacity(1);
        validateBudget(List.of(step));
        long id = nextJob++;
        Job job = new Job(
                id,
                planId,
                step.tool(),
                step.args(),
                step.priority() == null ? plan.priority() : step.priority(),
                step.priority() != null,
                nextOrder,
                JobState.PENDING,
                step.guards(),
                step.onUnmet(),
                step.onFailure() == null ? plan.onFailure() : step.onFailure(),
                step.maxAttempts(),
                0,
                List.of(),
                "",
                0,
                step.principal(),
                step.creativeSensitive());
        nextOrder += 100;
        jobs.put(id, job);
        return job;
    }

    public void updatePlanCursor(long planId, PlanCursor cursor) {
        PlanRecord plan = plans.get(planId);
        if (plan == null || plan.definition() == null || cursor == null)
            throw new IllegalArgumentException("unknown structured plan");
        plans.put(planId, plan.withCursor(cursor));
    }

    private void putNew(long id, long planId, QueuedStep step, PlanOptions options, List<Long> ids) {
        List<Long> after =
                step.afterSteps().stream().map(index -> ids.get(index - 1)).toList();
        Job job = new Job(
                id,
                planId,
                step.tool(),
                step.args(),
                step.priority() == null ? options.priority() : step.priority(),
                step.priority() != null,
                nextOrder,
                JobState.PENDING,
                step.guards(),
                step.onUnmet(),
                step.onFailure() == null ? options.onFailure() : step.onFailure(),
                step.maxAttempts(),
                0,
                after,
                "",
                0,
                step.principal(),
                step.creativeSensitive());
        nextOrder += 100;
        jobs.put(id, job);
    }

    private void ensureJobCapacity(int add) {
        if (jobs.size() + add > limits.maxJobs()) throw new IllegalArgumentException("queue has too many jobs");
    }

    private void validateBudget(List<QueuedStep> add) {
        validateBudget(add, 0);
    }

    private void validateBudget(List<QueuedStep> add, long excludedJob) {
        int total = jobs.values().stream()
                .filter(j -> j.id() != excludedJob)
                .mapToInt(j -> j.args().toString().length())
                .sum();
        for (QueuedStep s : add) {
            int size = s.args().toString().length();
            if (size > limits.maxArgsChars()) throw new IllegalArgumentException("arguments are too large");
            total += size;
        }
        if (total > limits.maxQueueArgsChars()) throw new IllegalArgumentException("queue arguments are too large");
    }

    private void replace(Job old, JobState state, String result, int attempts, long retryAt) {
        jobs.put(
                old.id(),
                new Job(
                        old.id(),
                        old.planId(),
                        old.tool(),
                        old.args(),
                        old.priority(),
                        old.priorityExplicit(),
                        old.order(),
                        state,
                        old.guards(),
                        old.onUnmet(),
                        old.onFailure(),
                        old.maxAttempts(),
                        attempts,
                        old.after(),
                        result,
                        retryAt,
                        old.principal(),
                        old.creativeSensitive()));
    }
    /** Returns a job that was running when the world saved, but has no live task, to the pending pool. */
    public void requeue(long id) {
        Job job = require(id);
        if (job.state() == JobState.RUNNING) replace(job, JobState.PENDING, job.result(), job.attempts(), 0);
    }

    public void markRunning(long id) {
        Job job = require(id);
        replace(job, JobState.RUNNING, job.result(), job.attempts(), job.retryAtTick());
    }

    public void finish(long id, String summary) {
        Job job = require(id);
        replace(job, JobState.DONE, bound(summary), job.attempts(), 0);
    }

    public FailureOutcome fail(long id, String reason) {
        Job job = require(id);
        int attempts = job.attempts() + 1;
        if (attempts < job.maxAttempts()) {
            replace(job, JobState.PENDING, bound(reason), attempts, clock + 20L * attempts);
            return new FailureOutcome(jobs.get(id), true);
        }
        JobState state = job.onFailure() == FailurePolicy.SKIP ? JobState.SKIPPED : JobState.FAILED;
        replace(job, state, bound(reason), attempts, 0);
        if (job.onFailure() == FailurePolicy.STOP_PLAN && job.planId() != 0) cancelPlan(job.planId());
        return new FailureOutcome(jobs.get(id), false);
    }

    public Result cancel(long id) {
        return cancel(id, "cancelled");
    }

    public Result cancel(long id, String reason) {
        Job job = jobs.get(id);
        if (job == null) return Result.error("unknown job #" + id);
        if (terminal(job))
            return Result.error("#" + id + " is already " + job.state().name().toLowerCase());
        replace(job, JobState.CANCELLED, bound(reason), job.attempts(), 0);
        return Result.ok("cancelled #" + id);
    }

    public Result markMissingTool(long id) {
        Job job = jobs.get(id);
        if (job == null) return Result.error("unknown job #" + id);
        replace(job, JobState.CANCELLED, Job.MISSING_TOOL_RESULT, job.attempts(), 0);
        return Result.ok("cancelled #" + id);
    }

    public Result move(long jobId, long beforeJobId) {
        Job job = jobs.get(jobId), before = jobs.get(beforeJobId);
        if (job == null || before == null || job.planId() == 0 || job.planId() != before.planId())
            return Result.error("jobs must be in the same plan");
        if (job.state() != JobState.PENDING || before.state() != JobState.PENDING)
            return Result.error("only pending jobs can move");
        if (jobId == beforeJobId) return Result.error("a job cannot move before itself");
        List<Long> order = new ArrayList<>(idsByOrder());
        order.remove(jobId);
        order.add(order.indexOf(beforeJobId), jobId);
        applyOrder(order);
        return Result.ok("moved #" + jobId + " before #" + beforeJobId);
    }

    public Result setPriority(long id, int priority) {
        Job job = jobs.get(id);
        if (job == null) return Result.error("unknown job #" + id);
        jobs.put(
                id,
                new Job(
                        job.id(),
                        job.planId(),
                        job.tool(),
                        job.args(),
                        priority,
                        true,
                        job.order(),
                        job.state(),
                        job.guards(),
                        job.onUnmet(),
                        job.onFailure(),
                        job.maxAttempts(),
                        job.attempts(),
                        job.after(),
                        job.result(),
                        job.retryAtTick(),
                        job.principal(),
                        job.creativeSensitive()));
        return Result.ok("#" + id + " priority is now " + priority);
    }

    public Result setPlanPriority(long planId, int priority) {
        PlanRecord plan = plans.get(planId);
        if (plan == null) return Result.error("unknown plan #" + planId);
        plans.put(
                planId,
                new PlanRecord(
                        plan.id(), plan.goal(), priority, plan.addedTick(), plan.deadlineTick(), plan.onFailure()));
        for (Job job : jobsOfPlan(planId))
            if (job.state() == JobState.PENDING && !job.priorityExplicit())
                jobs.put(
                        job.id(),
                        new Job(
                                job.id(),
                                job.planId(),
                                job.tool(),
                                job.args(),
                                priority,
                                false,
                                job.order(),
                                job.state(),
                                job.guards(),
                                job.onUnmet(),
                                job.onFailure(),
                                job.maxAttempts(),
                                job.attempts(),
                                job.after(),
                                job.result(),
                                job.retryAtTick(),
                                job.principal(),
                                job.creativeSensitive()));
        return Result.ok("plan #" + planId + " priority is now " + priority);
    }

    public Result updateArgs(long id, JsonObject args) {
        Job current = jobs.get(id);
        if (current == null) return Result.error("job cannot be updated");
        return updateArgs(id, args, current.principal(), current.creativeSensitive());
    }

    public Result updateArgs(long id, JsonObject args, java.util.UUID principal, boolean creativeSensitive) {
        Job job = jobs.get(id);
        if (job == null || job.state() != JobState.PENDING) return Result.error("job cannot be updated");
        if (args == null || args.toString().length() > limits.maxArgsChars())
            return Result.error("arguments are too large");
        int total = jobs.values().stream()
                        .filter(j -> j.id() != id)
                        .mapToInt(j -> j.args().toString().length())
                        .sum()
                + args.toString().length();
        if (total > limits.maxQueueArgsChars()) return Result.error("queue arguments are too large");
        jobs.put(
                id,
                new Job(
                        job.id(),
                        job.planId(),
                        job.tool(),
                        args,
                        job.priority(),
                        job.priorityExplicit(),
                        job.order(),
                        job.state(),
                        job.guards(),
                        job.onUnmet(),
                        job.onFailure(),
                        job.maxAttempts(),
                        job.attempts(),
                        job.after(),
                        job.result(),
                        job.retryAtTick(),
                        principal,
                        creativeSensitive));
        return Result.ok("updated #" + id);
    }

    public Result merge(long firstId, long secondId, JsonObject args) {
        Job first = jobs.get(firstId), second = jobs.get(secondId);
        if (first == null || second == null || first.planId() == 0 || first.planId() != second.planId())
            return Result.error("jobs cannot merge");
        if (first.state() != JobState.PENDING
                || second.state() != JobState.PENDING
                || first.order() >= second.order()
                || !(second.after().isEmpty() || second.after().equals(List.of(firstId))))
            return Result.error("jobs cannot merge");
        jobs.put(
                firstId,
                copy(
                        first,
                        args == null ? first.args() : args,
                        first.priority(),
                        first.priorityExplicit(),
                        first.order(),
                        first.state(),
                        first.after(),
                        first.result(),
                        first.retryAtTick()));
        jobs.remove(secondId);
        repointDependents(secondId, firstId, List.of());
        return Result.ok("merged #" + firstId + " and #" + secondId);
    }

    public Result replace(long oldId, List<QueuedStep> steps) {
        Job old = jobs.get(oldId);
        if (old == null
                || old.planId() == 0
                || (old.state() != JobState.PENDING
                        && old.state() != JobState.FAILED
                        && old.state() != JobState.CANCELLED)) return Result.error("job cannot be replaced");
        if (steps == null || steps.isEmpty()) return Result.error("replacement needs at least one step");
        if (steps.size() > limits.maxStepsPerAdd()) return Result.error("too many replacement steps");
        try {
            validateBudget(steps, oldId);
            if (jobs.size() - 1 + steps.size() > limits.maxJobs())
                throw new IllegalArgumentException("queue has too many jobs");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
        for (int i = 0; i < steps.size(); i++)
            for (int after : steps.get(i).afterSteps())
                if (after < 1 || after > i) return Result.error("after may only name an earlier step");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) ids.add(nextJob++);
        PlanRecord plan = plans.get(old.planId());
        PlanOptions options = new PlanOptions(plan.priority(), plan.onFailure(), 0);
        for (int i = 0; i < steps.size(); i++) putNew(ids.get(i), old.planId(), steps.get(i), options, ids);
        for (int i = 0; i < ids.size(); i++) {
            Job job = jobs.get(ids.get(i));
            List<Long> deps = new ArrayList<>(i == 0 ? old.after() : job.after());
            if (i > 0 && !deps.contains(ids.get(i - 1))) deps.add(ids.get(i - 1));
            jobs.put(
                    job.id(),
                    copy(
                            job,
                            job.args(),
                            job.priority(),
                            job.priorityExplicit(),
                            job.order(),
                            job.state(),
                            deps,
                            job.result(),
                            job.retryAtTick()));
        }
        repointDependents(oldId, ids.getLast(), ids);
        List<Long> order = new ArrayList<>();
        for (long id : idsByOrder()) {
            if (ids.contains(id)) continue;
            if (id == oldId) order.addAll(ids);
            else order.add(id);
        }
        jobs.remove(oldId);
        applyOrder(order);
        return Result.ok("replaced #" + oldId + " with " + ids.size() + " jobs ("
                + ids.stream().map(id -> "#" + id).collect(java.util.stream.Collectors.joining(", ")) + ")");
    }

    private void repointDependents(long from, long to, List<Long> skip) {
        for (Job job : new ArrayList<>(jobs.values())) {
            if (!job.after().contains(from) || skip.contains(job.id())) continue;
            List<Long> deps = new ArrayList<>();
            for (long dep : job.after()) {
                long target = dep == from ? to : dep;
                if (!deps.contains(target)) deps.add(target);
            }
            jobs.put(
                    job.id(),
                    copy(
                            job,
                            job.args(),
                            job.priority(),
                            job.priorityExplicit(),
                            job.order(),
                            job.state(),
                            deps,
                            job.result(),
                            job.retryAtTick()));
        }
    }

    private List<Long> idsByOrder() {
        return jobs.values().stream()
                .sorted(Comparator.comparingLong(Job::order))
                .map(Job::id)
                .toList();
    }
    /** Renumbers every job 100, 200, ... in the given sequence, so positions never collide. */
    private void applyOrder(List<Long> sequence) {
        long order = 100;
        for (long id : sequence) {
            Job job = jobs.get(id);
            jobs.put(
                    id,
                    copy(
                            job,
                            job.args(),
                            job.priority(),
                            job.priorityExplicit(),
                            order,
                            job.state(),
                            job.after(),
                            job.result(),
                            job.retryAtTick()));
            order += 100;
        }
        nextOrder = order;
    }

    private static String bound(String text) {
        return text == null ? "" : text.length() <= MAX_STORED_RESULT ? text : text.substring(0, MAX_STORED_RESULT);
    }

    private static Job copy(
            Job j,
            JsonObject args,
            int priority,
            boolean explicit,
            long order,
            JobState state,
            List<Long> after,
            String result,
            long retryAt) {
        return new Job(
                j.id(),
                j.planId(),
                j.tool(),
                args,
                priority,
                explicit,
                order,
                state,
                j.guards(),
                j.onUnmet(),
                j.onFailure(),
                j.maxAttempts(),
                j.attempts(),
                after,
                result,
                retryAt,
                j.principal(),
                j.creativeSensitive());
    }

    public Result cancelPlan(long planId) {
        PlanRecord plan = plans.get(planId);
        if (plan == null) return Result.error("unknown plan #" + planId);
        for (Job j : jobsOfPlan(planId)) if (!terminal(j)) replace(j, JobState.CANCELLED, "cancelled", j.attempts(), 0);
        if (plan.definition() != null && plan.cursor() != null)
            plans.put(
                    planId,
                    plan.withCursor(new PlanCursor(
                            List.of(),
                            plan.cursor().selectedBranches(),
                            plan.cursor().repeatIterations(),
                            plan.cursor().startTick(),
                            plan.cursor().startDistance(),
                            "",
                            null)));
        return Result.ok("cancelled plan #" + planId);
    }

    public Result remove(long id) {
        return jobs.remove(id) == null ? Result.error("unknown job #" + id) : Result.ok("removed #" + id);
    }

    public Result clear() {
        int pc = plans.size(), jc = jobs.size();
        if (jc == 0 && pc == 0) return Result.ok("queue is already empty");
        plans.clear();
        jobs.clear();
        return Result.ok(
                "cleared " + pc + (pc == 1 ? " plan and " : " plans and ") + jc + (jc == 1 ? " job" : " jobs"));
    }

    public Result retry(long id) {
        Job j = jobs.get(id);
        if (j == null || j.state() != JobState.FAILED) return Result.error("job cannot retry");
        replace(j, JobState.PENDING, "", 0, 0);
        return Result.ok("#" + id + " will retry");
    }

    public Result skip(long id) {
        Job j = jobs.get(id);
        if (j == null) return Result.error("unknown job #" + id);
        if (j.state() != JobState.PENDING && j.state() != JobState.FAILED && j.state() != JobState.CANCELLED)
            return Result.error("job cannot be skipped");
        replace(j, JobState.SKIPPED, "skipped", j.attempts(), 0);
        return Result.ok("skipped #" + id);
    }

    public List<Long> expiredPlans() {
        return plans.values().stream()
                .filter(p -> p.deadlineTick() > 0 && p.deadlineTick() <= clock)
                .map(PlanRecord::id)
                .toList();
    }

    public void archivePlan(long id) {
        PlanRecord p = plans.get(id);
        if (p == null) return;
        List<Job> planJobs = jobsOfPlan(id);
        boolean success = planJobs.stream().allMatch(j -> j.state() == JobState.DONE || j.state() == JobState.SKIPPED);
        archivePlan(id, success, success ? "finished" : "failed");
    }

    public void archivePlan(long id, boolean success, String summary) {
        PlanRecord p = plans.remove(id);
        if (p == null) return;
        List<Job> planJobs = jobsOfPlan(id);
        archives.add(new PlanArchive(id, p.goal(), success, planJobs.size(), summary, clock));
        planJobs.forEach(j -> jobs.remove(j.id()));
        while (archives.size() > limits.maxArchives()) archives.removeFirst();
    }

    public static boolean terminal(Job j) {
        return switch (j.state()) {
            case DONE, FAILED, SKIPPED, CANCELLED -> true;
            default -> false;
        };
    }

    private Job require(long id) {
        Job j = jobs.get(id);
        if (j == null) throw new IllegalArgumentException("unknown job #" + id);
        return j;
    }
}
