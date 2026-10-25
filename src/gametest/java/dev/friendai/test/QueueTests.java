package dev.friendai.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.llm.model.ToolCall;
import dev.friendai.queue.FailurePolicy;
import dev.friendai.queue.GuardAction;
import dev.friendai.queue.QueuedStep;
import dev.friendai.task.builtin.StayTask;
import dev.friendai.task.TaskSettings;
import dev.friendai.tool.ToolExecutor;
import dev.friendai.tool.ToolResult;
import dev.friendai.tool.builtin.FriendaiTools;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** End-to-end queue tool validation on the server. */
public final class QueueTests {
    private static ToolResult run(ToolExecutor tools, String name, String json) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        return tools.execute(new ToolCall("queue-test", name, args, json, null));
    }

    @GameTest
    public void queueAddRejectsBadStepsWithoutAdding(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = FriendaiTools.create(() -> 32).bind(companion);
        ToolResult badDependency = run(tools, "queue_add",
                "{\"steps\":[{\"tool\":\"drop_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:dirt\\\",\\\"count\\\":1}\",\"after\":[1]}]}");
        h.assertTrue(badDependency.error(), "forward dependency should fail: " + badDependency.content());
        h.assertTrue(companion.taskRunner().queue().jobCount() == 0
                && companion.taskRunner().queue().planCount() == 0, "invalid input partially changed the queue");
        h.succeed();
    }

    @GameTest
    public void queueShowIncludesPlanAndJobs(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = FriendaiTools.create(() -> 32).bind(companion);
        ToolResult added = run(tools, "queue_add",
                "{\"goal\":\"drop dirt\",\"steps\":[{\"tool\":\"drop_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:dirt\\\",\\\"count\\\":1}\"}]}");
        h.assertTrue(!added.error(), "queue_add failed: " + added.content());
        ToolResult shown = run(tools, "queue_show", "{}");
        h.assertTrue(!shown.error() && shown.content().contains("drop dirt") && shown.content().contains("drop_items"),
                "queue_show did not include the added plan: " + shown.content());
        h.succeed();
    }

    @GameTest
    public void activeQueueJobSurvivesEntitySave(GameTestHelper h) {
        CompanionEntity original = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        original.setOwner(UUID.randomUUID());
        original.taskRunner().enqueue(new QueuedStep("stay", new JsonObject(), null, List.of(),
                GuardAction.WAIT, FailurePolicy.ASK, 1, List.of()), ctx -> new StayTask());
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        original.saveWithoutId(output);
        CompanionEntity restored = h.spawn(FriendaiEntities.COMPANION, 5, 2, 5);
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), output.buildResult()));
        h.assertTrue(restored.taskRunner().queue().jobCount() == 1, "queued job did not restore");
        h.assertTrue(restored.taskRunner().queue().jobs().getFirst().state() == dev.friendai.queue.JobState.RUNNING,
                "active job state did not restore");
        h.assertTrue(restored.taskRunner().current().map(task -> task.type().equals("stay")).orElse(false),
                "active task did not restore");
        h.succeed();
    }
}
