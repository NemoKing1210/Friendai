package dev.friendai.server.execution.persistence;

import dev.friendai.FriendaiMod;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.JobState;
import dev.friendai.execution.queue.PlanRecord;
import dev.friendai.execution.queue.QueueSave;
import dev.friendai.features.acquisition.minecraft.task.SearchResourceTask;
import dev.friendai.features.knowledge.search.SearchLedger;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.Task;
import dev.friendai.task.TaskState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Owns bounded execution capture and the complete restore decision before tasks can run. */
public final class TaskPersistenceCoordinator {
    private final Supplier<ServerSettings> settings;
    private final TaskRestoreRegistry registry;
    private final Consumer<CompanionEntity> attachOwnerEvents;
    private final Consumer<UUID> invalidateTradingAuthority;
    private final Function<UUID, SearchLedger> searchLedger;

    public TaskPersistenceCoordinator(
            Supplier<ServerSettings> settings,
            TaskRestoreRegistry registry,
            Consumer<CompanionEntity> attachOwnerEvents,
            Consumer<UUID> invalidateTradingAuthority,
            Function<UUID, SearchLedger> searchLedger) {
        this.settings = settings;
        this.registry = registry;
        this.attachOwnerEvents = attachOwnerEvents;
        this.invalidateTradingAuthority = invalidateTradingAuthority;
        this.searchLedger = searchLedger;
    }

    public Optional<SavedExecution> capture(ExecutionCoordinator runner) {
        JobQueue queue = runner.queue();
        Optional<TaskState> activeTask = runner.persistenceTaskState();
        if (queue.jobCount() == 0 && queue.planCount() == 0 && queue.archives().isEmpty() && activeTask.isEmpty())
            return Optional.empty();
        long activeJob = runner.persistenceActiveJobId();
        return Optional.of(new SavedExecution(
                SavedExecution.CURRENT_VERSION, new QueueSave.Decoded(queue, activeJob), activeTask.orElse(null)));
    }

    public String encode(SavedExecution saved) {
        return SavedExecutionCodec.encode(saved);
    }

    public Optional<SavedExecution> decode(String encoded) {
        return SavedExecutionCodec.decode(encoded, settings.get().queueLimits());
    }

    public List<String> split(String encoded) {
        return SavedExecutionCodec.split(encoded);
    }

    public Optional<String> join(List<String> parts) {
        return SavedExecutionCodec.join(parts);
    }

    public RestoreReport restore(CompanionEntity companion, SavedExecution saved) {
        UUID owner = companion.ownerUuid();
        if (owner == null) return new RestoreReport(List.of(), List.of("execution has no owner"));

        // The task listener must exist before restore notices are placed on the runner.
        attachOwnerEvents.accept(companion);
        invalidateTradingAuthority.accept(owner);

        JobQueue queue = saved.queue().queue();
        List<String> restored = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        Set<Long> rejectedPlans = new HashSet<>();
        boolean creativeRejected = false;

        for (PlanRecord plan : List.copyOf(queue.plans())) {
            if (registry.requiresFreshAuthority(plan)) {
                rejectedPlans.add(plan.id());
                rejected.add("plan #" + plan.id() + ": trading authority expired");
            } else if (!registry.hasPlanTools(plan)) {
                rejectedPlans.add(plan.id());
                rejected.add("plan #" + plan.id() + ": required task is unavailable");
            }
        }
        for (Job job : List.copyOf(queue.jobs())) {
            if (job.planId() > 0 && rejectedPlans.contains(job.planId())) continue;
            if (registry.requiresFreshAuthority(job)) {
                if (job.planId() > 0) rejectedPlans.add(job.planId());
                else {
                    queue.cancel(job.id(), "trading authority expired after restart");
                    rejected.add("job #" + job.id() + ": trading authority expired");
                }
                continue;
            }
            if (!registry.hasTaskTool(job.tool())) {
                queue.markMissingTool(job.id());
                rejected.add("job #" + job.id() + ": task is unavailable");
                continue;
            }
            if (!registry.authorized(job, owner)) {
                if (job.planId() > 0) rejectedPlans.add(job.planId());
                else {
                    queue.cancel(job.id(), "saved actor no longer has access");
                    rejected.add("job #" + job.id() + ": actor no longer has access");
                }
                continue;
            }
            if (companion.creativeMode()
                    && job.creativeSensitive()
                    && job.state() != JobState.DONE
                    && job.state() != JobState.CANCELLED
                    && job.state() != JobState.SKIPPED) {
                if (job.planId() > 0) rejectedPlans.add(job.planId());
                else {
                    queue.cancel(job.id(), "creative task cancelled after reload");
                    rejected.add("job #" + job.id() + ": creative action requires a new request");
                }
                creativeRejected = true;
            }
        }
        for (long planId : rejectedPlans) {
            queue.cancelPlan(planId);
            queue.archivePlan(planId, false, "saved work requires a new request");
            rejected.add("plan #" + planId + ": saved work requires a new request");
        }

        Map<Long, dev.friendai.task.plan.StepFactory> factories = registry.stepFactories(queue);
        Map<Long, Map<String, dev.friendai.task.plan.PlanStepFactory>> planFactories = registry.planFactories(queue);
        long activeJobId = saved.queue().activeJobId();
        Task activeTask = null;
        TaskState checkpoint = saved.activeTask();
        Job activeJob = activeJobId == 0 ? null : queue.job(activeJobId).orElse(null);
        if (activeJobId > 0 && activeJob != null && activeJob.state() == JobState.RUNNING) {
            if (registry.interruptedSearch(activeJob)
                    || checkpoint != null && checkpoint.type().equals(SearchResourceTask.TYPE)) {
                searchLedger.apply(owner).interruptActive();
                if (activeJob.planId() > 0) {
                    queue.cancelPlan(activeJob.planId());
                    queue.archivePlan(activeJob.planId(), false, "resource search interrupted by restart");
                } else queue.cancel(activeJob.id(), "resource search interrupted by restart");
                rejected.add("job #" + activeJob.id() + ": resource search interrupted by restart");
            } else if (checkpoint != null) {
                activeTask = registry.restore(checkpoint, companion).orElse(null);
                if (activeTask == null) rejected.add("active task: checkpoint could not be restored");
            }
            if (activeTask == null
                    && queue.job(activeJobId)
                            .filter(job -> job.state() == JobState.RUNNING)
                            .isPresent()) {
                var factory = factories.get(activeJobId);
                if (factory != null) {
                    try {
                        activeTask =
                                factory.create(companion.executionCoordinator().activeTaskContext());
                    } catch (RuntimeException invalidTask) {
                        queue.cancel(activeJobId, "saved task could not be reconstructed");
                        rejected.add("job #" + activeJobId + ": saved task could not be reconstructed");
                    }
                }
            }
        } else if (activeJobId == 0 && checkpoint != null) {
            activeTask = registry.restore(checkpoint, companion).orElse(null);
            if (activeTask != null) restored.add("active task " + checkpoint.type());
            else rejected.add("active task: task type is unavailable");
        }

        if (activeJobId > 0 && activeTask != null) restored.add("job #" + activeJobId);
        for (Job job : queue.jobs()) {
            if (job.state() == JobState.PENDING && factories.containsKey(job.id())) restored.add("job #" + job.id());
        }
        for (PlanRecord plan : queue.plans()) {
            if (planFactories.containsKey(plan.id())) restored.add("plan #" + plan.id());
        }

        companion
                .executionCoordinator()
                .installRestoredState(
                        new QueueSave.Decoded(queue, activeTask == null ? 0 : activeJobId),
                        factories,
                        planFactories,
                        activeTask);
        reconcileFreeTimePlan(companion);
        if (creativeRejected)
            companion
                    .executionCoordinator()
                    .notice("Saved creative tasks were cancelled after reload; submit them again.");
        for (Job job : companion.executionCoordinator().queue().jobs()) {
            if (job.state() == JobState.CANCELLED && Job.MISSING_TOOL_RESULT.equals(job.result())) {
                companion
                        .executionCoordinator()
                        .notice("Saved queue job #" + job.id() + " needs repair: tool " + job.tool()
                                + " is unavailable");
            }
        }
        for (String message : rejected) FriendaiMod.LOGGER.info("Execution restore: {}", message);
        return new RestoreReport(restored, rejected);
    }

    public RestoreReport restoreEncoded(CompanionEntity companion, List<String> parts) {
        Optional<String> encoded = join(parts);
        if (encoded.isEmpty()) {
            FriendaiMod.LOGGER.warn("Discarding malformed saved execution for {}", companion.ownerUuid());
            reconcileFreeTimePlan(companion);
            return new RestoreReport(List.of(), List.of("saved execution is malformed"));
        }
        Optional<SavedExecution> saved = decode(encoded.get());
        if (saved.isEmpty()) {
            FriendaiMod.LOGGER.warn(
                    "Discarding unsupported or malformed saved execution for {}", companion.ownerUuid());
            reconcileFreeTimePlan(companion);
            return new RestoreReport(List.of(), List.of("saved execution format is unsupported"));
        }
        return restore(companion, saved.get());
    }

    /** Resolves persisted free-time state after a queue restore, including bodies with no saved queue. */
    public void reconcileFreeTimePlan(CompanionEntity companion) {
        UUID owner = companion.ownerUuid();
        if (owner == null || companion.level().getServer() == null) return;
        dev.friendai.core.FriendaiServerState.get(companion.level().getServer())
                .freeTime()
                .reconcileRestoredPlan(owner, id -> {
                    var status = companion.executionCoordinator().queue().planStatus(id);
                    return status == dev.friendai.execution.queue.PlanStatus.ACTIVE
                            || status == dev.friendai.execution.queue.PlanStatus.NEEDS_DECISION;
                });
    }
}
