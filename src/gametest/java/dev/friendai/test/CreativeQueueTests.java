package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.core.CreativeModeService;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.llm.model.ToolCall;
import dev.friendai.queue.FailurePolicy;
import dev.friendai.queue.GuardAction;
import dev.friendai.queue.JobState;
import dev.friendai.queue.QueuedStep;
import dev.friendai.task.builtin.StayTask;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.ToolExecutor;
import dev.friendai.tool.ToolResult;
import dev.friendai.tool.builtin.FriendaiTools;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

public final class CreativeQueueTests {
    private static Caller caller(ServerPlayer player, AccessLevel access) {
        return new Caller(player.getUUID(), player.getName().getString(), access);
    }

    private static ToolResult retry(ToolExecutor tools, long jobId, Caller caller) {
        JsonObject args = new JsonObject();
        args.addProperty("action", "retry");
        args.addProperty("job", jobId);
        return tools.execute(new ToolCall("retry", "queue_edit", args, args.toString(), null), caller);
    }

    @GameTest
    public void onlyOwnerCanRetryFailedCreativeJob(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer trusted = GameTestSupport.mockPlayerAt(h, 4, 2, 2);
        owner.level().getServer().getPlayerList().op(owner.nameAndId(),
                Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
        CompanionEntity companion = CompanionManager.get(h.getLevel().getServer()).summon(owner);
        h.assertTrue(CreativeModeService.requestMode(companion, owner, true) == CreativeModeService.Result.CHANGED,
                "operator owner could not enable creative mode");

        var runner = companion.taskRunner();
        runner.setPaused(true);
        long jobId = runner.enqueue(new QueuedStep("place_block", new JsonObject(), null, List.of(),
                        GuardAction.WAIT, FailurePolicy.ASK, 1, List.of(), owner.getUUID(), true),
                context -> new StayTask());
        runner.queue().fail(jobId, "simulated failed creative action");
        h.assertTrue(runner.queue().job(jobId).orElseThrow().state() == JobState.FAILED,
                "creative job did not reach FAILED state");

        ToolExecutor tools = FriendaiTools.create(() -> 32).bind(companion);
        ToolResult trustedRetry = retry(tools, jobId, caller(trusted, AccessLevel.TRUSTED));
        h.assertTrue(trustedRetry.error(), "trusted caller retried an owner creative job: " + trustedRetry.content());
        h.assertTrue(runner.queue().job(jobId).orElseThrow().state() == JobState.FAILED,
                "denied trusted retry changed the job state");

        runner.setPaused(false);
        runner.tick();
        h.assertTrue(runner.queue().job(jobId).orElseThrow().state() == JobState.FAILED,
                "denied retry still ran the creative task");

        ToolResult ownerRetry = retry(tools, jobId, caller(owner, AccessLevel.OWNER));
        h.assertTrue(!ownerRetry.error(), "owner retry was refused: " + ownerRetry.content());
        runner.tick();
        h.assertTrue(runner.queue().job(jobId).orElseThrow().state() == JobState.RUNNING,
                "authorized owner retry did not run the creative task");
        h.succeed();
    }
}
