package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.server.agent.chat.ChatRouter;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Exact owner orders run through the tool registry even when the model is unavailable. */
public class LocalOrderTests {
    @GameTest(maxTicks = 200)
    public void followOrderStartsWithoutAModel(GameTestHelper h) {
        var owner = online(h, "local-follow", 61);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        offline(h, owner);
        ChatRouter.onChat(GameTestSupport.runtime(h), owner, "@ follow me");
        h.assertValueEqual(currentTask(companion), "follow", "the order must start the follow task");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void stayAndComeUseTheirTasks(GameTestHelper h) {
        var owner = online(h, "local-stay", 63);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        offline(h, owner);
        ChatRouter.onChat(GameTestSupport.runtime(h), owner, "@ stay");
        h.assertValueEqual(currentTask(companion), "stay", "stay");
        companion.executionCoordinator().stop("test");
        ChatRouter.onChat(GameTestSupport.runtime(h), owner, "@ come here");
        h.assertValueEqual(currentTask(companion), "go_to", "come");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void stopEndsTheCurrentOrder(GameTestHelper h) {
        var owner = online(h, "local-stop", 65);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        offline(h, owner);
        ChatRouter.onChat(GameTestSupport.runtime(h), owner, "@ stay");
        h.assertValueEqual(currentTask(companion), "stay", "stay first");
        ChatRouter.onChat(GameTestSupport.runtime(h), owner, "@ stop");
        String after = currentTask(companion);
        h.assertTrue(!"stay".equals(after), "stop must end the stay task, was " + after);
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aGuestCannotOrderTheCompanion(GameTestHelper h) {
        GameTestSupport.platformAt(h, 66, 16);
        var owner = GameTestSupport.onlinePlayer(h, "local-owner", 2, 67, 2, true);
        var guest = GameTestSupport.onlinePlayer(h, "local-guest", 3, 67, 3, true);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner.player());
        offline(h, owner.player());
        ChatRouter.onChat(GameTestSupport.runtime(h), guest.player(), "@ follow me");
        h.assertTrue(companion.executionCoordinator().current().isEmpty(), "a guest must not start work");
        h.assertTrue(
                guest.drainSystemMessages().stream()
                        .anyMatch(message -> message.getString().contains("access")),
                "a guest must be told they have no access");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aQuestionIsNotAnOrder(GameTestHelper h) {
        var owner = online(h, "local-question", 69);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        offline(h, owner);
        ChatRouter.onChat(GameTestSupport.runtime(h), owner, "@ why did you follow me?");
        h.assertTrue(companion.executionCoordinator().current().isEmpty(), "a question must not start a task");
        h.succeed();
    }

    private static net.minecraft.server.level.ServerPlayer online(GameTestHelper h, String name, int y) {
        GameTestSupport.platformAt(h, y - 1, 16);
        return GameTestSupport.onlinePlayer(h, name, 2, y, 2, true).player();
    }

    private static void offline(GameTestHelper h, net.minecraft.server.level.ServerPlayer owner) {
        GameTestSupport.runtime(h)
                .sessions()
                .setGatewayOverride(
                        owner.getUUID(),
                        (system, messages, tools) ->
                                CompletableFuture.failedFuture(new IllegalStateException("offline in test")));
    }

    private static String currentTask(CompanionEntity companion) {
        return companion
                .executionCoordinator()
                .current()
                .map(task -> task.type())
                .orElse("");
    }
}
