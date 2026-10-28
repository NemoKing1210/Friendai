package dev.friendai.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.llm.model.ToolCall;
import dev.friendai.queue.FailurePolicy;
import dev.friendai.queue.GuardAction;
import dev.friendai.queue.Job;
import dev.friendai.queue.JobQueue;
import dev.friendai.queue.JobState;
import dev.friendai.queue.PlanOptions;
import dev.friendai.queue.PlanStatus;
import dev.friendai.queue.QueueSave;
import dev.friendai.queue.QueuedStep;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.StayTask;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.ToolExecutor;
import dev.friendai.tool.ToolResult;
import dev.friendai.tool.builtin.FriendaiTools;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** End-to-end queue tool validation on the server. */
public final class QueueTests {
    private static final class ThrowOnStartTask implements Task {
        @Override
        public String type() {
            return "throw_on_restore_start";
        }

        @Override
        public void start(TaskContext context) {
            throw new IllegalStateException("restore start failed");
        }

        @Override
        public TaskStatus tick(TaskContext context) {
            return TaskStatus.RUNNING;
        }

        @Override
        public void stop(TaskContext context) {}

        @Override
        public String describe() {
            return "throwing restore task";
        }

        @Override
        public String resultSummary() {
            return "unexpected completion";
        }
    }

    private static ToolResult run(ToolExecutor tools, String name, String json) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        return tools.execute(new ToolCall("queue-test", name, args, json, null));
    }

    private static ToolResult run(ToolExecutor tools, String name, String json, Caller caller) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        return tools.execute(new ToolCall("queue-test", name, args, json, null), caller);
    }

    @GameTest
    public void queueAddRejectsBadStepsWithoutAdding(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = FriendaiTools.create(() -> 32).bind(companion);
        ToolResult badDependency = run(
                tools,
                "queue_add",
                "{\"steps\":[{\"tool\":\"drop_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:dirt\\\",\\\"count\\\":1}\",\"after\":[1]}]}");
        h.assertTrue(badDependency.error(), "forward dependency should fail: " + badDependency.content());
        h.assertTrue(
                companion.executionCoordinator().queue().jobCount() == 0
                        && companion.executionCoordinator().queue().planCount() == 0,
                "invalid input partially changed the queue");
        h.succeed();
    }

    @GameTest
    public void queueShowIncludesPlanAndJobs(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        UUID owner = UUID.randomUUID();
        companion.setOwner(owner);
        Caller caller = new Caller(owner, "queue-test", AccessLevel.OWNER);
        ToolExecutor tools = FriendaiTools.create(() -> 32).bind(companion);
        ToolResult added = run(
                tools,
                "queue_add",
                "{\"goal\":\"drop dirt\",\"steps\":[{\"tool\":\"drop_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:dirt\\\",\\\"count\\\":1}\"}]}",
                caller);
        h.assertTrue(!added.error(), "queue_add failed: " + added.content());
        ToolResult shown = run(tools, "queue_show", "{}", caller);
        h.assertTrue(
                !shown.error()
                        && shown.content().contains("drop dirt")
                        && shown.content().contains("drop_items"),
                "queue_show did not include the added plan: " + shown.content());
        h.succeed();
    }

    @GameTest
    public void activeQueueJobSurvivesEntitySave(GameTestHelper h) {
        CompanionEntity original = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        UUID owner = UUID.randomUUID();
        original.setOwner(owner);
        original.executionCoordinator()
                .enqueue(
                        new QueuedStep(
                                "stay",
                                new JsonObject(),
                                null,
                                List.of(),
                                GuardAction.WAIT,
                                FailurePolicy.ASK,
                                1,
                                List.of(),
                                owner,
                                false),
                        ctx -> new StayTask());
        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        original.saveWithoutId(output);
        CompanionEntity restored = h.spawn(FriendaiEntities.COMPANION, 5, 2, 5);
        restored.load(
                TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), output.buildResult()));
        h.assertTrue(restored.executionCoordinator().queue().jobCount() == 1, "queued job did not restore");
        h.assertTrue(
                restored.executionCoordinator().queue().jobs().getFirst().state()
                        == dev.friendai.queue.JobState.RUNNING,
                "active job state did not restore");
        h.assertTrue(
                restored.executionCoordinator()
                        .current()
                        .map(task -> task.type().equals("stay"))
                        .orElse(false),
                "active task did not restore");
        h.succeed();
    }

    @GameTest
    public void restoredAskPlanWaitsAfterTaskStartFailure(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator execution = new ExecutionCoordinator(companion, () -> 32, () -> true);
        JobQueue queue = new JobQueue(companion.serverSettings().queueLimits());
        JobQueue.Added plan = queue.addPlan(
                "restored decision",
                List.of(new QueuedStep(
                        "throw_on_restore_start",
                        new JsonObject(),
                        null,
                        List.of(),
                        GuardAction.WAIT,
                        FailurePolicy.ASK,
                        1,
                        List.of())),
                new PlanOptions(0, FailurePolicy.ASK, 0));
        long jobId = plan.jobIds().getFirst();
        queue.markRunning(jobId);

        execution.installRestoredState(new QueueSave.Decoded(queue, jobId), Map.of(), Map.of(), new ThrowOnStartTask());
        execution.tick();

        Job failed = execution.queue().job(jobId).orElseThrow();
        h.assertTrue(failed.state() == JobState.FAILED, "failed restored task should stay failed");
        h.assertTrue(
                execution.queue().planStatus(plan.planId()) == PlanStatus.NEEDS_DECISION,
                "failed ASK plan should still require a decision");
        h.assertTrue(execution.current().isEmpty(), "default follow must wait for the owner's decision");
        h.succeed();
    }
}
