package dev.friendai.task.builtin;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.knowledge.ResourceSite;
import dev.friendai.pathing.GoalPoint;
import dev.friendai.pathing.Pos;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.nav.Navigator;

/** Travels to a remembered resource site and waits for fresh direct evidence. */
public final class VisitResourceTask implements Task {
    private static final int SIGHT_BUDGET_TICKS = 80;
    private final long siteId;
    private final String dimension;
    private final int maxTicks;
    private Navigator navigator;
    private ResourceCandidates.Verification result = ResourceCandidates.Verification.PENDING;
    private int ticks;
    private int waiting;
    private boolean observing;

    public VisitResourceTask(long siteId, String dimension, int maxTicks) {
        this.siteId = siteId;
        this.dimension = dimension;
        this.maxTicks = Math.max(1, maxTicks);
    }

    public ResourceCandidates.Verification result() { return result; }

    @Override public String type() { return "visit_resource"; }

    @Override
    public void start(TaskContext c) {
        ticks = 0;
        waiting = 0;
        observing = false;
        navigator = new Navigator(c.companion(), false);
        String currentDimension = c.level().dimension().identifier().toString();
        if (!dimension.equals(currentDimension)) {
            result = ResourceCandidates.Verification.UNLOADED;
            return;
        }
        ResourceSite site = FriendaiServerState.get(c.level().getServer()).resources()
                .site(c.companion(), siteId).orElse(null);
        if (site == null) {
            result = ResourceCandidates.Verification.ABSENT;
            return;
        }
        Pos approach = site.approach();
        navigator.setGoal(new GoalPoint(approach.x() + 0.5, approach.y(), approach.z() + 0.5, 3.0, 2.0));
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (++ticks > maxTicks) {
            result = ResourceCandidates.Verification.UNLOADED;
            return TaskStatus.FAILED;
        }
        if (result == ResourceCandidates.Verification.ABSENT
                || result == ResourceCandidates.Verification.UNLOADED) return TaskStatus.FAILED;
        ResourceCandidates candidates = FriendaiServerState.get(c.level().getServer()).resources();
        if (!observing) {
            Navigator.State state = navigator.tick(c);
            if (state == Navigator.State.UNREACHABLE) {
                result = ResourceCandidates.Verification.OCCLUDED;
                return TaskStatus.FAILED;
            }
            if (state != Navigator.State.ARRIVED) return TaskStatus.RUNNING;
            candidates.verify(c.companion(), siteId);
            observing = true;
        }
        if (candidates.siteVisible(c.companion(), siteId)) {
            result = ResourceCandidates.Verification.PRESENT;
            return TaskStatus.SUCCESS;
        }
        if (++waiting >= SIGHT_BUDGET_TICKS) {
            result = ResourceCandidates.Verification.OCCLUDED;
            return TaskStatus.FAILED;
        }
        return TaskStatus.RUNNING;
    }

    @Override public void stop(TaskContext c) { if (navigator != null) navigator.stop(c); }
    @Override public String describe() { return "visiting remembered resource site " + siteId; }
    @Override public String resultSummary() { return result.name().toLowerCase(java.util.Locale.ROOT); }
}
