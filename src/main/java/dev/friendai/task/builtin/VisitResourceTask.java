package dev.friendai.task.builtin;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.knowledge.ResourceSite;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.nav.ResourceJourney;

/** Travels to a remembered resource site and waits for fresh direct evidence. */
public final class VisitResourceTask implements Task {
    private static final int SIGHT_BUDGET_TICKS = 240;
    private final long siteId;
    private final String dimension;
    private final int maxTicks;
    private ResourceJourney journey;
    private ResourceCandidates.Verification result = ResourceCandidates.Verification.PENDING;
    private int ticks;
    private int waiting;
    private boolean observing;

    public VisitResourceTask(long siteId, String dimension, int maxTicks) {
        this.siteId = siteId;
        this.dimension = dimension;
        this.maxTicks = Math.max(1, maxTicks);
    }

    public ResourceCandidates.Verification result() {
        return result;
    }

    @Override
    public String type() {
        return "visit_resource";
    }

    @Override
    public void start(TaskContext c) {
        ticks = 0;
        waiting = 0;
        observing = false;
        String currentDimension = c.level().dimension().identifier().toString();
        if (!dimension.equals(currentDimension)) {
            result = ResourceCandidates.Verification.UNLOADED;
            return;
        }
        ResourceSite site = FriendaiServerState.get(c.level().getServer())
                .resources()
                .site(c.companion(), siteId)
                .orElse(null);
        if (site == null) {
            result = ResourceCandidates.Verification.ABSENT;
            return;
        }
        Pos approach = site.approach();
        journey = new ResourceJourney(
                c,
                dimension,
                approach,
                null,
                FriendaiServerState.get(c.level().getServer()).resources().trails(c.companion()),
                false);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (++ticks > maxTicks) {
            result = ResourceCandidates.Verification.UNLOADED;
            return TaskStatus.FAILED;
        }
        if (result == ResourceCandidates.Verification.ABSENT || result == ResourceCandidates.Verification.UNLOADED)
            return TaskStatus.FAILED;
        ResourceCandidates candidates =
                FriendaiServerState.get(c.level().getServer()).resources();
        if (!observing) {
            ResourceJourney.State state = journey.tick(c);
            if (state == ResourceJourney.State.BLOCKED || state == ResourceJourney.State.WAITING_FOR_CHUNK) {
                result = ResourceCandidates.Verification.OCCLUDED;
                return TaskStatus.FAILED;
            }
            if (state != ResourceJourney.State.ARRIVED) return TaskStatus.RUNNING;
            candidates.verify(c.companion(), siteId);
            observing = true;
        }
        // The companion can shift while reaching the approach. A pending ray is tied to its
        // original eye position and is discarded if that position changes, so refresh focused
        // checks until fresh evidence arrives or the sight budget expires.
        candidates.verify(c.companion(), siteId);
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

    @Override
    public void stop(TaskContext c) {
        if (journey != null) journey.stop(c);
    }

    @Override
    public String describe() {
        return "visiting remembered resource site " + siteId;
    }

    @Override
    public String resultSummary() {
        String value = "site=" + siteId + "; phase=" + (observing ? "inspect" : "travel")
                + "; result=" + result.name().toLowerCase(java.util.Locale.ROOT)
                + "; ticks=" + ticks + "/" + maxTicks
                + (observing ? "; sight_wait=" + waiting + "/" + SIGHT_BUDGET_TICKS : "");
        return value.length() <= 600 ? value : value.substring(0, 597) + "...";
    }
}
