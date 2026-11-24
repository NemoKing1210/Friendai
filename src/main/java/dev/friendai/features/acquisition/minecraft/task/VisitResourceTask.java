package dev.friendai.features.acquisition.minecraft.task;

import dev.friendai.features.knowledge.ResourceSite;
import dev.friendai.features.navigation.minecraft.nav.ResourceJourney;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;

/** Travels to a remembered resource site and waits for fresh direct evidence. */
public final class VisitResourceTask implements Task {
    private static final int SIGHT_BUDGET_TICKS = 240;
    private final long siteId;
    private final String dimension;
    private final int maxTicks;
    private ResourceJourney journey;
    private ResourceCandidates.VerificationTicket verification;
    private ResourceCandidates.VerificationReport report;
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

    /** What each inspected member settled, so only a proven removal is ever forgotten. */
    public ResourceCandidates.VerificationReport report() {
        return report;
    }

    @Override
    public String type() {
        return "visit_resource";
    }

    @Override
    public void start(TaskContext c) {
        ticks = 0;
        waiting = 0;
        result = ResourceCandidates.Verification.PENDING;
        observing = false;
        verification = null;
        report = null;
        String currentDimension = c.level().dimension().identifier().toString();
        if (!dimension.equals(currentDimension)) {
            result = ResourceCandidates.Verification.UNLOADED;
            return;
        }
        ResourceSite site = c.resources().site(c.companion(), siteId).orElse(null);
        if (site == null) {
            result = ResourceCandidates.Verification.ABSENT;
            return;
        }
        Pos approach = site.approach();
        journey =
                new ResourceJourney(c, dimension, approach, null, c.resources().trails(c.companion()), false);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (!dimension.equals(c.level().dimension().identifier().toString())) {
            result = ResourceCandidates.Verification.UNLOADED;
            return TaskStatus.FAILED;
        }
        if (++ticks > maxTicks) {
            result = ResourceCandidates.Verification.TIMED_OUT;
            return TaskStatus.FAILED;
        }
        if (result == ResourceCandidates.Verification.ABSENT
                || result == ResourceCandidates.Verification.UNLOADED
                || result == ResourceCandidates.Verification.TIMED_OUT) return TaskStatus.FAILED;
        ResourceCandidates candidates = c.resources();
        if (!observing) {
            ResourceJourney.State state = journey.tick(c);
            if (state == ResourceJourney.State.BLOCKED || state == ResourceJourney.State.WAITING_FOR_CHUNK) {
                result = ResourceCandidates.Verification.OCCLUDED;
                return TaskStatus.FAILED;
            }
            if (state != ResourceJourney.State.ARRIVED) return TaskStatus.RUNNING;
            verification = candidates.beginVerification(c.companion(), siteId);
            observing = true;
        }
        // The ticket keeps one direct result per exact member. A moved companion causes the observer
        // to re-aim that request; only completed rays from the reached viewpoint can settle the site.
        report = candidates.verificationReport(c.companion(), verification);
        result = report.verdict();
        if (result == ResourceCandidates.Verification.PRESENT) {
            return TaskStatus.SUCCESS;
        }
        if (result == ResourceCandidates.Verification.ABSENT
                || result == ResourceCandidates.Verification.OCCLUDED
                || result == ResourceCandidates.Verification.UNLOADED) return TaskStatus.FAILED;
        if (++waiting >= SIGHT_BUDGET_TICKS) {
            // A budget that ran out is not evidence of an empty site: keep the members nothing settled.
            result = ResourceCandidates.Verification.TIMED_OUT;
            return TaskStatus.FAILED;
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        if (journey != null) journey.stop(c);
    }

    /**
     * A visit exists to prove the remembered blocks are still there. Only a completed ray can prove the opposite,
     * member by member: a blocked path, a chunk that failed to load or a sight budget that ran out proves nothing,
     * so forgetting a block then would delete real knowledge. A hidden member of a site with one visible removal
     * therefore survives, and the same viewpoint is not inspected again at once.
     */
    @Override
    public void onTerminal(TaskContext c) {
        if (!observing || report == null) return;
        c.resources().confirmAbsent(c.companion(), report);
        if (report.verdict() != ResourceCandidates.Verification.PRESENT && report.blockedSightline()) {
            c.resources().noteOcclusion(c.companion(), verification);
        }
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
                + (observing ? "; sight_wait=" + waiting + "/" + SIGHT_BUDGET_TICKS : "")
                + (report == null || report.removed().isEmpty()
                        ? ""
                        : "; removed=" + report.removed().size());
        return value.length() <= 600 ? value : value.substring(0, 597) + "...";
    }
}
