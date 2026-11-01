package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.features.navigation.minecraft.task.StayTask;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

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
}
