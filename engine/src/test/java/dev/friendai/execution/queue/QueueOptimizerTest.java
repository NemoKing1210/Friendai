package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueueOptimizerTest {
    private static final MergePolicy COUNTS = tool -> "mine_blocks".equals(tool) ? "count" : null;

    private static QueuedStep mine(int count) {
        return mine(count, null, List.of(), UUID.randomUUID());
    }

    private static QueuedStep mine(int count, Integer priority, List<Guard> guards, UUID principal) {
        JsonObject args = new JsonObject();
        args.addProperty("count", count);
        return new QueuedStep("mine_blocks", args, priority, guards, GuardAction.WAIT, null, 1, List.of(), principal);
    }

    private static long firstJob(JobQueue queue) {
        return queue.jobs().getFirst().id();
    }

    @Test
    void mergesAdjacentCountSteps() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        UUID owner = UUID.randomUUID();
        q.addPlan(
                "mine", List.of(mine(3, null, List.of(), owner), mine(6, null, List.of(), owner)), PlanOptions.DEFAULT);
        assertTrue(QueueOptimizer.apply(q, null, COUNTS).text().contains("count=9"));
        assertEquals(1, q.jobCount());
        assertEquals(9, q.jobs().getFirst().args().get("count").getAsInt());
    }

    @Test
    void doesNotMergeDifferentToolsOrPastSixtyFour() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        UUID owner = UUID.randomUUID();
        q.addPlan(
                "mine",
                List.of(mine(60, null, List.of(), owner), mine(5, null, List.of(), owner)),
                PlanOptions.DEFAULT);
        assertEquals("nothing to merge", QueueOptimizer.apply(q, null, COUNTS).text());
        assertEquals(2, q.jobCount());
    }

    @Test
    void theToolOwnLimitBoundsTheCombinedCount() {
        MergePolicy twoBottles = new MergePolicy() {
            @Override
            public String countArgument(String tool) {
                return "brew_potion".equals(tool) ? "count" : null;
            }

            @Override
            public int mergeLimit(String tool) {
                return 3;
            }
        };
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        UUID owner = UUID.randomUUID();
        q.addPlan("brew", List.of(brew(2, owner), brew(2, owner)), PlanOptions.DEFAULT);
        assertEquals(
                "nothing to merge", QueueOptimizer.apply(q, null, twoBottles).text());
        assertEquals(2, q.jobCount());

        JobQueue fits = new JobQueue(QueueLimits.DEFAULTS);
        fits.addPlan("brew", List.of(brew(2, owner), brew(1, owner)), PlanOptions.DEFAULT);
        assertTrue(QueueOptimizer.apply(fits, null, twoBottles).text().contains("count=3"));
        assertEquals(1, fits.jobCount());
    }

    @Test
    void anUnsettledJobBetweenCandidatesKeepsItsOwnBoundary() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        JobQueue.Added added = q.addPlan(
                "mine",
                List.of(
                        mine(3, null, List.of(), owner),
                        mine(1, null, List.of(), other),
                        mine(6, null, List.of(), owner)),
                PlanOptions.DEFAULT);
        assertEquals("nothing to merge", QueueOptimizer.apply(q, null, COUNTS).text());
        assertEquals(3, q.jobCount());
        assertEquals(
                JobState.PENDING, q.job(added.jobIds().get(2)).orElseThrow().state());
    }

    @Test
    void aSettledJobNoLongerSeparatesItsNeighbours() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        JobQueue.Added added = q.addPlan(
                "mine",
                List.of(
                        mine(3, null, List.of(), owner),
                        mine(1, null, List.of(), other),
                        mine(6, null, List.of(), owner)),
                PlanOptions.DEFAULT);
        q.cancel(added.jobIds().get(1), "no longer needed");
        assertTrue(QueueOptimizer.apply(q, null, COUNTS).text().contains("count=9"));
        assertEquals(2, q.jobCount());
    }

    @Test
    void workAskedForByDifferentOwnersNeverFolds() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        UUID owner = UUID.randomUUID();
        q.addPlan(
                "mine",
                List.of(mine(3, null, List.of(), owner), mine(6, null, List.of(), UUID.randomUUID())),
                PlanOptions.DEFAULT);
        assertEquals("nothing to merge", QueueOptimizer.apply(q, null, COUNTS).text());
        assertEquals(2, q.jobCount());
    }

    @Test
    void aGuardedStepIsNeverFolded() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        UUID owner = UUID.randomUUID();
        Guard guard = new Guard.TimeElapsed(100);
        q.addPlan(
                "mine",
                List.of(mine(3, null, List.of(guard), owner), mine(6, null, List.of(guard), owner)),
                PlanOptions.DEFAULT);
        assertEquals("nothing to merge", QueueOptimizer.apply(q, null, COUNTS).text());
        assertEquals(2, q.jobCount());
    }

    @Test
    void aHeldJobKeepsItsPlace() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        UUID owner = UUID.randomUUID();
        JobQueue.Added added = q.addPlan(
                "mine", List.of(mine(3, null, List.of(), owner), mine(6, null, List.of(), owner)), PlanOptions.DEFAULT);
        q.setHold(HoldKey.job(added.jobIds().get(1)), QueueHold.defer("waiting", q.clock() + 200, null));
        assertEquals("nothing to merge", QueueOptimizer.apply(q, null, COUNTS).text());
        assertEquals(2, q.jobCount());
        assertTrue(q.jobHeld(added.jobIds().get(1)));
    }

    @Test
    void onePassMergesAWholeRun() {
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        UUID owner = UUID.randomUUID();
        q.addPlan(
                "mine",
                List.of(
                        mine(2, null, List.of(), owner),
                        mine(3, null, List.of(), owner),
                        mine(4, null, List.of(), owner)),
                PlanOptions.DEFAULT);
        String text = QueueOptimizer.apply(q, null, COUNTS).text();
        assertTrue(text.contains("count=5"), text);
        assertTrue(text.contains("count=9"), text);
        assertEquals(1, q.jobCount());
        assertEquals(9, q.job(firstJob(q)).orElseThrow().args().get("count").getAsInt());
    }

    private static QueuedStep brew(int bottles, UUID principal) {
        JsonObject args = new JsonObject();
        args.addProperty("potion", "minecraft:swiftness");
        args.addProperty("count", bottles);
        return new QueuedStep("brew_potion", args, null, List.of(), GuardAction.WAIT, null, 1, List.of(), principal);
    }
}
