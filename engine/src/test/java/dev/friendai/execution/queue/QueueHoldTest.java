package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueueHoldTest {
    private static final class Facts implements GuardFacts {
        Boolean online;

        @Override
        public int countItems(String idOrTag) {
            return 0;
        }

        @Override
        public int emptySlots() {
            return 0;
        }

        @Override
        public float healthFraction() {
            return 1f;
        }

        @Override
        public int ownerDistance() {
            return 0;
        }

        @Override
        public int countBlocks(String idOrTag, int radius) {
            return 0;
        }

        @Override
        public String dimension() {
            return "minecraft:overworld";
        }

        @Override
        public Boolean ownerOnline() {
            return online;
        }
    }

    private static JobQueue queueWithOneJob() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        queue.addPlan(
                "test",
                List.of(new QueuedStep(
                        "wait", new JsonObject(), 0, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of())),
                PlanOptions.DEFAULT);
        return queue;
    }

    @Test
    void aManualPauseNeverWakes() {
        QueueHold hold = QueueHold.pause("owner asked");
        Facts facts = new Facts();
        for (long clock = 0; clock < 10_000; clock += 500) {
            assertFalse(hold.satisfied(clock, facts), "pause at " + clock);
        }
        assertTrue(hold.describe(0).contains("paused"));
        assertTrue(hold.describe(0).contains("owner asked"));
    }

    @Test
    void aDeferNeedsBothTheDelayAndTheGuard() {
        Facts facts = new Facts();
        facts.online = true;
        QueueHold hold = QueueHold.defer("waiting", 100, new Guard.OwnerOnline());

        assertFalse(hold.satisfied(99, facts), "before the delay");
        assertTrue(hold.satisfied(100, facts), "delay and guard both hold");

        facts.online = false;
        assertFalse(hold.satisfied(100, facts), "guard false after the delay");
        facts.online = null;
        assertFalse(hold.satisfied(100, facts), "unknown is not true");

        QueueHold delayOnly = QueueHold.defer("", 50, null);
        assertTrue(delayOnly.satisfied(50, facts));
        QueueHold guardOnly = QueueHold.defer("", 0, new Guard.OwnerOnline());
        facts.online = true;
        assertTrue(guardOnly.satisfied(0, facts));
        assertFalse(guardOnly.satisfied(0, new Facts()));
    }

    @Test
    void malformedDeferIsRejectedAndReasonIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> QueueHold.defer("", 0, null));
        String reason = "x".repeat(QueueHold.MAX_REASON + 50);
        assertEquals(QueueHold.MAX_REASON, QueueHold.pause(reason).reason().length());
    }

    @Test
    void theHoldMapIsBoundedAndReleasable() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        for (int i = 1; i <= JobQueue.MAX_HOLDS; i++) {
            queue.setHold(HoldKey.job(i), QueueHold.pause("x"));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> queue.setHold(HoldKey.job(JobQueue.MAX_HOLDS + 1), QueueHold.pause("x")));
        assertTrue(queue.release(HoldKey.job(1)));
        assertFalse(queue.release(HoldKey.job(1)));
        queue.setHold(HoldKey.job(JobQueue.MAX_HOLDS + 1), QueueHold.pause("x"));
        assertTrue(queue.hold(HoldKey.job(JobQueue.MAX_HOLDS + 1)).isPresent());
    }

    @Test
    void holdsRoundTripWithRemainingTicksRebasedOnTheClock() {
        JobQueue queue = queueWithOneJob();
        long jobId = queue.jobs().getFirst().id();
        queue.setHold(HoldKey.job(jobId), QueueHold.defer("soon", queue.clock() + 50, null));
        for (int i = 0; i < 10; i++) {
            queue.advanceClock();
        }
        JsonObject saved = QueueSave.encode(queue, 0);
        JobQueue loaded =
                QueueSave.decode(saved, QueueLimits.DEFAULTS).orElseThrow().queue();
        QueueHold hold = loaded.hold(HoldKey.job(jobId)).orElseThrow();

        assertEquals(40, hold.remaining(loaded.clock()), "10 ticks elapsed, 40 remain");
        assertFalse(hold.satisfied(loaded.clock(), new Facts()), "the delay is still running");
    }

    @Test
    void planAndJobHoldsBlockReadinessAndShowAReason() {
        JobQueue queue = queueWithOneJob();
        long planId = queue.plans().getFirst().id();
        Job job = queue.jobs().getFirst();
        Facts facts = new Facts();

        assertTrue(QueuePolicy.ready(queue, job, facts));
        queue.setHold(HoldKey.plan(planId), QueueHold.pause("owner asked"));
        assertFalse(QueuePolicy.ready(queue, job, facts));
        assertTrue(QueuePolicy.notReadyReason(queue, job, facts).contains("paused"));

        queue.release(HoldKey.plan(planId));
        assertTrue(QueuePolicy.ready(queue, job, facts));

        queue.setHold(HoldKey.job(job.id()), QueueHold.defer("later", queue.clock() + 20, null));
        assertFalse(QueuePolicy.ready(queue, job, facts));
        for (int i = 0; i < 20; i++) {
            queue.advanceClock();
        }
        assertTrue(QueuePolicy.ready(queue, job, facts), "the delay elapsed");
    }

    @Test
    void aJobHoldDoesNotHoldItsOwnPlanOrAnotherJob() {
        JobQueue queue = queueWithTwoIndependentJobs();
        long planId = queue.plans().getFirst().id();
        long first = queue.jobsOfPlan(planId).get(0).id();
        long second = queue.jobsOfPlan(planId).get(1).id();
        Job firstJob = queue.job(first).orElseThrow();
        Job secondJob = queue.job(second).orElseThrow();
        Facts facts = new Facts();

        queue.setHold(HoldKey.job(first), QueueHold.pause("first paused"));
        assertFalse(QueuePolicy.ready(queue, firstJob, facts), "the paused job waits");
        assertTrue(QueuePolicy.ready(queue, secondJob, facts), "its plan and its neighbour keep running");
        assertTrue(queue.hold(HoldKey.plan(planId)).isEmpty(), "no plan hold was created");

        queue.setHold(HoldKey.plan(planId), QueueHold.pause("plan paused"));
        assertFalse(QueuePolicy.ready(queue, secondJob, facts), "the plan hold reaches every step");
        assertTrue(QueuePolicy.notReadyReason(queue, secondJob, facts).contains("plan paused"));

        assertTrue(queue.release(HoldKey.job(first)));
        assertFalse(QueuePolicy.ready(queue, firstJob, facts), "the plan still holds the first job");

        queue.release(HoldKey.plan(planId));
        assertTrue(QueuePolicy.ready(queue, firstJob, facts));
        assertTrue(QueuePolicy.ready(queue, secondJob, facts));
    }

    @Test
    void aFinishedJobDropsItsOwnHoldAndACancelledPlanDropsItsWholeScope() {
        JobQueue queue = queueWithTwoIndependentJobs();
        long planId = queue.plans().getFirst().id();
        long first = queue.jobsOfPlan(planId).get(0).id();
        long second = queue.jobsOfPlan(planId).get(1).id();

        queue.setHold(HoldKey.plan(planId), QueueHold.pause("waiting on the owner"));
        queue.setHold(HoldKey.job(first), QueueHold.pause("held"));
        queue.finish(first, "done");
        assertTrue(queue.hold(HoldKey.job(first)).isEmpty(), "a terminal job keeps no hold");
        assertTrue(queue.hold(HoldKey.plan(planId)).isPresent(), "finishing one job leaves the plan hold");

        queue.setHold(HoldKey.job(second), QueueHold.pause("held"));
        queue.cancelPlan(planId);
        assertTrue(queue.hold(HoldKey.plan(planId)).isEmpty(), "cancelling a plan drops the plan hold");
        assertTrue(queue.hold(HoldKey.job(second)).isEmpty(), "and the holds of the jobs it cancels");
    }

    /** A plan whose two steps depend on nothing, so either can be ready on its own. */
    private static JobQueue queueWithTwoIndependentJobs() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        queue.addPlan(
                "two steps",
                List.of(
                        new QueuedStep(
                                "wait",
                                new JsonObject(),
                                null,
                                List.of(),
                                GuardAction.WAIT,
                                FailurePolicy.ASK,
                                1,
                                List.of()),
                        new QueuedStep(
                                "wait",
                                new JsonObject(),
                                null,
                                List.of(),
                                GuardAction.WAIT,
                                FailurePolicy.ASK,
                                1,
                                List.of())),
                PlanOptions.DEFAULT);
        return queue;
    }
}
