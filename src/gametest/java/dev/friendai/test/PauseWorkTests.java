package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.features.navigation.minecraft.task.GoToTask;
import dev.friendai.features.navigation.minecraft.task.StayTask;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** The owner's pause holds the runner and a resume continues the same work to its end. */
public class PauseWorkTests {
    @GameTest(maxTicks = 900)
    public void anOwnerPauseHoldsWorkUntilResumed(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var coordinator = companion.executionCoordinator();
        coordinator.resume();
        coordinator.enqueue(
                new QueuedStep(
                        "stay",
                        new JsonObject(),
                        null,
                        List.of(),
                        GuardAction.WAIT,
                        FailurePolicy.ASK,
                        1,
                        List.of(),
                        owner.getUUID(),
                        false),
                context -> new StayTask(60));

        h.succeedWhen(() -> {
            for (int i = 0; i < 40; i++) {
                coordinator.tick();
            }
            h.assertValueEqual(
                    coordinator.current().map(dev.friendai.task.Task::type).orElse("none"),
                    "stay",
                    "the stay should be running");

            coordinator.setManualPause(true);
            for (int i = 0; i < 30; i++) {
                coordinator.tick();
            }
            h.assertValueEqual(
                    coordinator.current().map(dev.friendai.task.Task::type).orElse("none"),
                    "stay",
                    "a paused task must not finish while it is held");

            coordinator.setManualPause(false);
            h.assertTrue(!coordinator.suspended(), "resume must clear the hold");
            h.assertTrue(!coordinator.manualPause(), "resume must clear the owner hold");
            for (int i = 0; i < 240; i++) {
                coordinator.tick();
            }
            h.assertTrue(
                    !"stay"
                            .equals(coordinator
                                    .current()
                                    .map(dev.friendai.task.Task::type)
                                    .orElse("none")),
                    "a resumed task must run to its end, current="
                            + coordinator.current().map(t -> t.describe()).orElse("none"));
            h.assertValueEqual(coordinator.queue().jobCount(), 0, "the resumed job must be finished and removed");
        });
    }

    @GameTest(maxTicks = 600)
    public void anOwnerPauseStopsAWalkingBody(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 8);
        var coordinator = companion.executionCoordinator();
        coordinator.resume();
        Vec3 target = h.absoluteVec(new Vec3(12.5, 2, 8.5));
        coordinator.start(new GoToTask(target), "go_to");

        Vec3[] held = {null};
        Vec3[] last = {null};
        int[] moves = {0};
        int[] pausedTicks = {0};
        h.succeedWhen(() -> {
            coordinator.tick();
            Vec3 now = companion.position();
            if (held[0] == null) {
                if (last[0] != null && now.distanceTo(last[0]) > 0.01) {
                    moves[0]++;
                }
                last[0] = now;
                if (moves[0] < 10) {
                    return;
                }
                coordinator.setManualPause(true);
                held[0] = now;
                return;
            }
            if (pausedTicks[0] < 30) {
                h.assertTrue(
                        now.distanceTo(held[0]) < 0.05,
                        "a paused companion kept walking " + now.distanceTo(held[0]) + " blocks");
                pausedTicks[0]++;
                return;
            }
            if (pausedTicks[0] == 30) {
                coordinator.setManualPause(false);
                pausedTicks[0]++;
                return;
            }
            h.assertTrue(
                    now.distanceTo(target) <= 2.0,
                    "a resumed companion did not finish walking: " + now + " vs " + target);
        });
    }

    @GameTest(maxTicks = 100)
    public void anOwnerPauseHaltsAMovingBody(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 8, 2, 8);
        companion.executionCoordinator().resume();
        // A body already carrying momentum is what a paused walk, leap or swim leaves behind: the runner stops feeding
        // the move control, and without the pause clearing it the body keeps sliding.
        companion.setDeltaMovement(0.3, 0.0, 0.0);
        Vec3 held = companion.position();
        companion.executionCoordinator().setManualPause(true);
        // The first check must come after the body has had ticks to move, or it trivially measures zero.
        h.runAfterDelay(
                5,
                () -> h.succeedWhen(() -> {
                    companion.executionCoordinator().tick();
                    h.assertTrue(
                            companion.position().distanceTo(held) < 0.05,
                            "a paused companion kept moving "
                                    + companion.position().distanceTo(held) + " blocks");
                }));
    }
}
