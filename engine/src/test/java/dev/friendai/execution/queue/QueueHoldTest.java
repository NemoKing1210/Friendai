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
            queue.setHold(i, QueueHold.pause("x"));
        }
        assertThrows(IllegalArgumentException.class, () -> queue.setHold(JobQueue.MAX_HOLDS + 1, QueueHold.pause("x")));
        assertTrue(queue.release(1));
        assertFalse(queue.release(1));
        queue.setHold(JobQueue.MAX_HOLDS + 1, QueueHold.pause("x"));
        assertTrue(queue.hold(JobQueue.MAX_HOLDS + 1).isPresent());
    }

    @Test
    void holdsRoundTripWithRemainingTicksRebasedOnTheClock() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        queue.setHold(7, QueueHold.defer("soon", queue.clock() + 50, null));
        for (int i = 0; i < 10; i++) {
            queue.advanceClock();
        }
        JsonObject saved = QueueSave.encode(queue, 0);
        JobQueue loaded =
                QueueSave.decode(saved, QueueLimits.DEFAULTS).orElseThrow().queue();
        QueueHold hold = loaded.hold(7).orElseThrow();

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
        queue.setHold(planId, QueueHold.pause("owner asked"));
        assertFalse(QueuePolicy.ready(queue, job, facts));
        assertTrue(QueuePolicy.notReadyReason(queue, job, facts).contains("paused"));

        queue.release(planId);
        assertTrue(QueuePolicy.ready(queue, job, facts));

        queue.setHold(job.id(), QueueHold.defer("later", queue.clock() + 20, null));
        assertFalse(QueuePolicy.ready(queue, job, facts));
        for (int i = 0; i < 20; i++) {
            queue.advanceClock();
        }
        assertTrue(QueuePolicy.ready(queue, job, facts), "the delay elapsed");
    }
}
