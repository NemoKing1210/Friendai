package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.features.navigation.minecraft.task.GoToTask;
import dev.friendai.features.navigation.minecraft.task.StayTask;
import dev.friendai.server.execution.ExecutionHold;
import dev.friendai.server.execution.HoldReason;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.TaskStatus;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;

/**
 * Whoever takes the body over withdraws the previous locomotion command: a route, the move control's wanted point and
 * horizontal momentum all stop at the hand-over, and the original task resumes from the live position.
 *
 * <p>The stages run on a per-tick callback and flip {@code done}; the sequence only checks that flag. Vanilla
 * {@code succeedWhen} retries a throwing assertion and treats the first normal return as success, so a stage machine
 * that returned between stages would pass on its first tick without walking anywhere.
 */
public class MovementHandoffTests {
    /** Ticks a resumed walk may take before the test gives up on it with a readable reason. */
    private static final int RESUME_BUDGET = 400;

    /** What a finished task reported, filtered to the walk the test is about. */
    private static final class Outcome implements TaskEventSink {
        TaskStatus status;

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            if ("go_to".equals(toolName)) this.status = status;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            if ("go_to".equals(toolName)) status = TaskStatus.FAILED;
        }

        @Override
        public void onJobFinished(long jobId, long planId, String toolName, TaskStatus status, String summary) {
            if ("go_to".equals(toolName)) this.status = status;
        }
    }

    private static void assertNotAbandoned(GameTestHelper h, int waited, String what) {
        h.assertTrue(waited < RESUME_BUDGET, what + " after " + RESUME_BUDGET + " ticks");
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 900)
    public void aFrozenWalkStopsAndResumesOnce(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 8);
        var coordinator = companion.executionCoordinator();
        coordinator.resume();
        Outcome out = new Outcome();
        coordinator.setEventSink(out);
        Vec3 goal = h.absoluteVec(new Vec3(12.5, 2, 8.5));
        // Only work that belongs to a job is frozen; a direct task would be replaced instead.
        QueuedStep step = new QueuedStep(
                "go_to", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        coordinator.enqueuePlan(
                "walk the platform", List.of(step), List.of(context -> new GoToTask(goal)), PlanOptions.DEFAULT);

        boolean[] done = {false};
        int[] stage = {0};
        int[] still = {0};
        int[] waited = {0};
        Vec3[] held = {null};
        Vec3 start = companion.position();
        h.onEachTick(() -> {
            if (done[0]) return;
            coordinator.tick();
            Vec3 now = companion.position();
            String current = coordinator.current().map(t -> t.type()).orElse("none");
            if (stage[0] == 0) {
                if (now.distanceTo(start) < 1.5) return;
                // An urgent direct task takes the body: the queued walk is frozen, not cancelled.
                coordinator.start(new StayTask(40), "stay");
                h.assertValueEqual(
                        coordinator.current().map(t -> t.type()).orElse("none"),
                        "stay",
                        "the urgent task takes the body");
                held[0] = now;
                stage[0] = 1;
                return;
            }
            if (stage[0] == 1) {
                h.assertTrue(
                        now.distanceTo(held[0]) < 0.05,
                        "a frozen walk kept moving " + now.distanceTo(held[0]) + " blocks");
                // A ready job ends the stay at once, so the urgent task may be shorter than its own budget.
                if ("stay".equals(current)) {
                    still[0]++;
                    return;
                }
                h.assertTrue(still[0] >= 5, "the urgent task ended before the frozen body could be observed");
                stage[0] = 2;
                return;
            }
            if (out.status == null) {
                assertNotAbandoned(h, ++waited[0], "the resumed walk never reported an outcome");
                return;
            }
            h.assertValueEqual(out.status, TaskStatus.SUCCESS, "the resumed walk finishes once, successfully");
            h.assertTrue(now.distanceTo(goal) <= 2.0, "the resumed walk stopped short: " + now + " vs " + goal);
            done[0] = true;
        });
        h.succeedWhen(() -> h.assertTrue(done[0], "the hand-off never reached its last stage"));
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 900)
    public void aTemporaryHoldStopsAWalk(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 8);
        var coordinator = companion.executionCoordinator();
        coordinator.resume();
        Vec3 goal = h.absoluteVec(new Vec3(12.5, 2, 8.5));
        coordinator.start(new GoToTask(goal), "go_to");

        boolean[] done = {false};
        int[] stage = {0};
        int[] still = {0};
        int[] waited = {0};
        Vec3[] held = {null};
        ExecutionHold[] hold = {null};
        Vec3 start = companion.position();
        h.onEachTick(() -> {
            if (done[0]) return;
            coordinator.tick();
            Vec3 now = companion.position();
            if (stage[0] == 0) {
                if (now.distanceTo(start) < 1.5) return;
                hold[0] = coordinator.acquireHold(HoldReason.COMBAT);
                held[0] = now;
                stage[0] = 1;
                return;
            }
            if (stage[0] == 1) {
                h.assertTrue(
                        now.distanceTo(held[0]) < 0.05,
                        "a held walk kept moving " + now.distanceTo(held[0]) + " blocks");
                if (++still[0] < 30) return;
                hold[0].close();
                stage[0] = 2;
                return;
            }
            if (now.distanceTo(goal) > 2.0) {
                assertNotAbandoned(h, ++waited[0], "the walk did not resume after the hold");
                return;
            }
            done[0] = true;
        });
        h.succeedWhen(() -> h.assertTrue(done[0], "the hand-off never reached its last stage"));
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 300)
    public void anInventoryHoldStopsMomentum(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 8, 2, 8);
        companion.executionCoordinator().resume();
        // A body already carrying momentum is what an interrupted walk, leap or swim leaves behind.
        companion.setDeltaMovement(0.3, 0.0, 0.0);
        Vec3 held = companion.position();
        companion.addInventoryViewer();
        // The check must come after the body has had ticks to move, or it trivially measures zero.
        h.runAfterDelay(5, () -> {
            h.assertTrue(
                    companion.position().distanceTo(held) < 0.05,
                    "an inventory hold let the body slide "
                            + companion.position().distanceTo(held) + " blocks");
            companion.removeInventoryViewer();
            h.assertTrue(!companion.executionCoordinator().held(), "closing the window releases the inventory hold");
            h.succeed();
        });
    }
}
