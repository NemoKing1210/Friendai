package dev.friendai.server.execution;

import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.PlanRecord;
import dev.friendai.execution.queue.PlanStatus;
import dev.friendai.execution.queue.QueueText;
import dev.friendai.server.execution.ActiveTaskController.ActiveTask;
import dev.friendai.task.Task;
import java.util.Optional;

/**
 * The runner's cancel, skip and clear operations. Every one of them runs inside the coordinator's transition, keeps
 * the single queue and the one transition order, and hands control back the way the runner does: a cleanup task if the
 * stopped work asked for one, otherwise the next job or the default activity.
 */
final class QueueMutationService {
    private final ExecutionCoordinator coordinator;

    QueueMutationService(ExecutionCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    void stop(String reason) {
        coordinator.inTransition(() -> {
            ActiveTask active = coordinator.activeTask();
            if (active == null || active.defaultActivity()) {
                stopActive(reason);
                return;
            }
            // Something was running, so the owner's stop is an interrupt: count it now and its wait when idle.
            coordinator
                    .metrics()
                    .interruptIssued(coordinator.companion().level().getGameTime());
            stopActive(reason);
        });
    }

    private void stopActive(String reason) {
        if (coordinator.activeTask() != null && coordinator.activeTask().interrupt()) {
            coordinator.cancelCurrent(reason);
            coordinator.resumeFrozen();
            return;
        }
        ActiveTask active = coordinator.activeTask();
        if (active != null && active.jobId() > 0) {
            Task cleanup = active.task().cleanupAfterCancel(coordinator.context());
            coordinator.cancelCurrent(reason);
            if (active.planId() > 0) {
                coordinator.queueDriver().cancelPlan(active.planId());
                coordinator.queueDriver().finishPlan(active.planId(), "stopped by request", false);
            } else {
                coordinator.queueDriver().cancelJob(active.jobId());
                coordinator.queueDriver().remove(active.jobId());
                coordinator.queueDriver().clearFactory(active.jobId());
            }
            if (cleanup != null) coordinator.begin(cleanup, "scaffold_cleanup", false, false);
            else coordinator.startNextOrDefault();
            return;
        }
        Task cleanup = active == null ? null : active.task().cleanupAfterCancel(coordinator.context());
        if (active != null) coordinator.cancelCurrent(reason);
        if (cleanup != null) coordinator.begin(cleanup, "scaffold_cleanup", false, false);
        else coordinator.startNextOrDefault();
    }

    void stopWithoutCleanup(String reason) {
        coordinator.inTransition(() -> stopWithoutCleanupActive(reason));
    }

    private void stopWithoutCleanupActive(String reason) {
        if (coordinator.activeTask() != null) coordinator.cancelCurrent(reason);
        for (PlanRecord plan : coordinator.queueDriver().queue().plans()) {
            coordinator.queueDriver().cancelPlan(plan.id());
            coordinator.queueDriver().finishPlan(plan.id(), reason, false);
        }
        coordinator.stopFrozen(reason);
        coordinator.queueDriver().clear();
        coordinator.queueDriver().clearFactories();
        coordinator.queueDriver().clearAwaitingDecisions();
        coordinator.startDefault();
    }

    /** Cancels stale work during a capability transition without reporting task failures to the brain. */
    void cancelForCapabilityChange() {
        coordinator.inTransition(this::cancelForCapabilityChangeActive);
    }

    private void cancelForCapabilityChangeActive() {
        ActiveTask active = coordinator.activeTask();
        Task cleanup = active == null ? null : active.task().cleanupAfterCancel(coordinator.context());
        ActiveTask frozen = coordinator.frozenTask();
        if (frozen != null) {
            cleanup =
                    coordinator.combineCleanup(cleanup, frozen.task().cleanupAfterCancel(coordinator.context(frozen)));
            coordinator.stopFrozen("creative authority changed");
        }
        if (active != null) {
            coordinator.activeTasks().stop(new StopRequest("creative authority changed"), coordinator.context(active));
        }
        coordinator.queueDriver().clear();
        coordinator.queueDriver().clearFactories();
        coordinator.queueDriver().clearPlanState();
        coordinator.queueDriver().clearAwaitingDecisions();
        if (cleanup != null) coordinator.begin(cleanup, "scaffold_cleanup", false, false);
        else coordinator.startDefault();
    }

    void stopAll(String reason) {
        coordinator.inTransition(() -> stopAllActive(reason));
    }

    private void stopAllActive(String reason) {
        ActiveTask active = coordinator.activeTask();
        Task cleanup = active == null ? null : active.task().cleanupAfterCancel(coordinator.context());
        ActiveTask frozen = coordinator.frozenTask();
        if (frozen != null) {
            cleanup =
                    coordinator.combineCleanup(cleanup, frozen.task().cleanupAfterCancel(coordinator.context(frozen)));
            coordinator.stopFrozen(reason);
        }
        if (active != null) coordinator.cancelCurrent(reason);
        for (PlanRecord p : coordinator.queueDriver().queue().plans())
            coordinator.queueDriver().finishPlan(p.id(), "stopped by request", false);
        coordinator.queueDriver().clear();
        coordinator.queueDriver().clearFactories();
        coordinator.queueDriver().clearAwaitingDecisions();
        if (cleanup != null) coordinator.begin(cleanup, "scaffold_cleanup", false, false);
        else coordinator.startDefault();
    }

    String clearQueue(String reason) {
        return coordinator.inTransition(() -> clearQueueActive(reason));
    }

    private String clearQueueActive(String reason) {
        ActiveTask active = coordinator.activeTask();
        int plans = coordinator.queueDriver().queue().plans().size();
        int jobs = coordinator.queueDriver().queue().jobCount();
        boolean stoppedActive = active != null && active.jobId() > 0;
        Task cleanup = stoppedActive ? active.task().cleanupAfterCancel(coordinator.context()) : null;
        ActiveTask frozen = coordinator.frozenTask();
        if (frozen != null) {
            cleanup =
                    coordinator.combineCleanup(cleanup, frozen.task().cleanupAfterCancel(coordinator.context(frozen)));
            coordinator.stopFrozen(reason);
        }
        if (stoppedActive) coordinator.cancelCurrent(reason);
        for (PlanRecord plan : coordinator.queueDriver().queue().plans())
            coordinator.queueDriver().finishPlan(plan.id(), reason, false);
        String result;
        if (plans == 0 && jobs == 0) {
            result = stoppedActive ? "stopped the active task" : QueueText.cleared(0, 0);
        } else {
            result = QueueText.cleared(plans, jobs);
            if (stoppedActive) result += " and stopped the active task";
        }
        coordinator.queueDriver().clear();
        coordinator.queueDriver().clearFactories();
        coordinator.queueDriver().clearAwaitingDecisions();
        if (cleanup != null) coordinator.begin(cleanup, "scaffold_cleanup", false, false);
        else coordinator.startNextOrDefault();
        return result;
    }

    void cancelJob(long id, String reason) {
        coordinator.inTransition(() -> cancelJobActive(id, reason));
    }

    private void cancelJobActive(long id, String reason) {
        Job original = coordinator.queueDriver().queue().job(id).orElse(null);
        ActiveTask active = coordinator.activeTask();
        Task cleanup = null;
        if (active != null && active.jobId() == id) {
            cleanup = active.task().cleanupAfterCancel(coordinator.context());
            coordinator.cancelCurrent(reason);
            coordinator.queueDriver().cancelJob(id);
            coordinator.queueDriver().clearFactory(id);
        } else {
            ActiveTask frozen = coordinator.frozenTask();
            if (frozen != null && frozen.jobId() == id) {
                cleanup = frozen.task().cleanupAfterCancel(coordinator.context(frozen));
                coordinator.stopFrozen(reason);
            }
            coordinator.queueDriver().cancelJob(id);
            coordinator.queueDriver().clearFactory(id);
        }
        Job j = coordinator.queueDriver().queue().job(id).orElse(null);
        if (j != null && j.planId() == 0) coordinator.queueDriver().remove(id);
        else if (j != null && coordinator.isStructuredPlan(j.planId())) {
            coordinator.queueDriver().cancelPlan(j.planId());
            coordinator.queueDriver().finishPlan(j.planId(), "action cancelled", false);
        } else if (j != null
                && j.planId() > 0
                && coordinator.queueDriver().queue().planStatus(j.planId()) != PlanStatus.ACTIVE)
            coordinator.queueDriver().finishPlan(j.planId(), "cancelled", false);
        if (original != null && original.planId() == 0 && (active == null || active.jobId() != id))
            coordinator.emit(sink -> sink.onCancelled((int) original.id(), original.tool(), reason));
        if (cleanup != null) coordinator.begin(cleanup, "scaffold_cleanup", false, false);
        else if (coordinator.activeTask() == null) coordinator.startNextOrDefault();
    }

    JobQueue.Result skipJob(long id) {
        return coordinator.inTransition(() -> skipJobActive(id));
    }

    private JobQueue.Result skipJobActive(long id) {
        JobQueue.Result result = coordinator.queueDriver().skipJob(id);
        if (!result.ok()) return result;
        coordinator.wakeQueue();
        if (coordinator.activeTask() == null) coordinator.startNextOrDefault();
        return result;
    }

    void cancelPlan(long id, String reason) {
        coordinator.inTransition(() -> cancelPlanActive(id, reason));
    }

    private void cancelPlanActive(long id, String reason) {
        ActiveTask active = coordinator.activeTask();
        Task cleanup = null;
        if (active != null && active.planId() == id) {
            cleanup = active.task().cleanupAfterCancel(coordinator.context());
            coordinator.cancelCurrent(reason);
        }
        ActiveTask frozen = coordinator.frozenTask();
        if (frozen != null && frozen.planId() == id) {
            cleanup =
                    coordinator.combineCleanup(cleanup, frozen.task().cleanupAfterCancel(coordinator.context(frozen)));
            coordinator.stopFrozen(reason);
        }
        coordinator.queueDriver().cancelPlan(id);
        coordinator.queueDriver().finishPlan(id, "cancelled", false);
        if (cleanup != null) coordinator.begin(cleanup, "scaffold_cleanup", false, false);
        else if (coordinator.activeTask() == null) coordinator.startNextOrDefault();
    }

    Optional<String> stopNamed(String name, String reason) {
        ActiveTask a = coordinator.activeTask();
        if (a == null || !(a.toolName().equals(name) || a.task().matches(name))) return Optional.empty();
        String description = a.task().describe();
        stop(reason);
        return Optional.of(description);
    }
}
