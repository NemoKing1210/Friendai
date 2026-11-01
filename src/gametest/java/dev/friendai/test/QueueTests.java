package dev.friendai.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.verify.OutcomeChecker;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.JobState;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.PlanStatus;
import dev.friendai.execution.queue.QueueSave;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.navigation.minecraft.task.StayTask;
import dev.friendai.server.agent.OutcomeFacts;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.queue.CompanionGuardFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

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

    /** Records what the coordinator tells the brain, so a stop can be told apart from a finish. */
    private static final class RecordingSink implements TaskEventSink {
        final List<String> finished = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            finished.add(toolName + ":" + status);
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            cancelled.add(toolName + ":" + reason);
        }
    }

    private static QueuedStep stayStep(UUID principal) {
        return new QueuedStep(
                "stay",
                new JsonObject(),
                null,
                List.of(),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                List.of(),
                principal,
                false);
    }

    private static ToolResult run(ToolExecutor tools, String name, String json) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        return tools.execute(new ToolCall("queue-test", name, args, json, null));
    }

    private static ToolResult run(ToolExecutor tools, String name, String json, Caller caller) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        return tools.execute(new ToolCall("queue-test", name, args, json, null), caller);
    }

    @GameTest(maxTicks = 200)
    public void optimizedJobsExecuteTheMergedArgumentsAsTheOriginalPrincipal(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        UUID owner = UUID.randomUUID();
        companion.setOwner(owner);
        Caller ownerCaller = new Caller(owner, "queue-test", AccessLevel.OWNER);
        Caller optimizer = new Caller(UUID.randomUUID(), "helper", AccessLevel.TRUSTED);
        ToolExecutor tools = GameTestToolRegistry.create(companion, () -> 32).bind(companion);
        var coordinator = companion.executionCoordinator();
        coordinator.suspend();
        ToolResult added = run(
                tools,
                "queue_add",
                "{\"steps\":["
                        + "{\"tool\":\"mine_blocks\",\"args\":\"{\\\"block\\\":\\\"minecraft:coal_ore\\\",\\\"count\\\":2}\"},"
                        + "{\"tool\":\"mine_blocks\",\"args\":\"{\\\"block\\\":\\\"minecraft:iron_ore\\\",\\\"count\\\":3}\"}],"
                        + "\"optimize\":true}",
                ownerCaller);
        h.assertTrue(!added.error(), "queue_add failed: " + added.content());
        var queue = companion.executionCoordinator().queue();
        var plan = queue.plans().getFirst();
        var jobs = queue.jobsOfPlan(plan.id());
        h.assertTrue(jobs.size() == 2, "the differing block keeps both jobs pending");
        long second = jobs.get(1).id();
        ToolResult updated = run(
                tools,
                "queue_edit",
                "{\"action\":\"update\",\"job\":" + second
                        + ",\"args\":\"{\\\"block\\\":\\\"minecraft:coal_ore\\\",\\\"count\\\":3}\"}",
                ownerCaller);
        h.assertTrue(!updated.error(), "update failed: " + updated.content());
        ToolResult optimized =
                run(tools, "queue_edit", "{\"action\":\"optimize\",\"plan\":" + plan.id() + "}", optimizer);
        h.assertTrue(!optimized.error(), "optimize failed: " + optimized.content());
        h.assertTrue(optimized.content().contains("count=5"), "the jobs should merge: " + optimized.content());
        coordinator.resume();
        String[] started = {null};
        boolean[] principalKept = {false};
        h.succeedWhen(() -> {
            coordinator.tick();
            if (started[0] == null) {
                var task = coordinator.current().orElse(null);
                if (task == null || !task.describe().startsWith("mining")) return;
                started[0] = task.describe();
                principalKept[0] =
                        owner.equals(coordinator.activeJob().orElseThrow().principal());
            }
            h.assertTrue(started[0].contains("mining 5"), "execution must use the merged count, got: " + started[0]);
            h.assertTrue(principalKept[0], "the optimized job keeps its original principal");
        });
    }

    @GameTest
    public void queueAddRejectsBadStepsWithoutAdding(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = GameTestToolRegistry.create(companion, () -> 32).bind(companion);
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
        ToolExecutor tools = GameTestToolRegistry.create(companion, () -> 32).bind(companion);
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
                        == dev.friendai.execution.queue.JobState.RUNNING,
                "active job state did not restore");
        h.assertTrue(
                restored.executionCoordinator().suspended(), "restored owner work waits while the owner is offline");
        h.assertTrue(
                restored.executionCoordinator().current().isEmpty(),
                "restored owner work must not start before the owner logs in");
        long jobId = restored.executionCoordinator().queue().jobs().getFirst().id();
        h.assertTrue(
                restored.executionCoordinator().persistenceActiveJobId() == jobId,
                "the deferred task is still the active work of its job for persistence");
        h.assertTrue(
                restored.executionCoordinator().persistenceTaskState().isPresent(),
                "the deferred checkpoint must survive a save");
        TagValueOutput secondOutput = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        restored.saveWithoutId(secondOutput);
        CompanionEntity again = h.spawn(FriendaiEntities.COMPANION, 6, 2, 6);
        again.load(TagValueInput.create(
                ProblemReporter.DISCARDING, h.getLevel().registryAccess(), secondOutput.buildResult()));
        h.assertTrue(
                again.executionCoordinator().queue().jobCount() == 1,
                "a save while the owner is away must keep the restored queue");
        h.assertTrue(
                again.executionCoordinator().queue().jobs().getFirst().state()
                        == dev.friendai.execution.queue.JobState.RUNNING,
                "the deferred job stays running across the save");
        h.assertTrue(again.executionCoordinator().suspended(), "the second restore also waits for the owner");
        CompanionManager.get(h.getLevel().getServer()).register(again);
        GameTestSupport.runtime(h.getLevel().getServer()).sessions().onOwnerJoined(owner);
        h.assertTrue(
                again.executionCoordinator()
                        .current()
                        .map(task -> task.type().equals("stay"))
                        .orElse(false),
                "the restored task starts when the owner joins");
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

    @GameTest(maxTicks = 200)
    public void theOutcomeSnapshotReadsHealthAndFoodOffTheBody(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        companion.setHealth(companion.getMaxHealth() * 0.5f);

        var snapshot = dev.friendai.server.agent.OutcomeFacts.snapshot(companion);
        h.assertValueEqual(snapshot.healthPercent(), 50, "the snapshot carries the body's health");
        h.assertTrue(snapshot.foodLevel() >= 0, "and its hunger: " + snapshot.foodLevel());
        h.succeed();
    }

    @GameTest(maxTicks = 300)
    public void theLastDirectJobCanBeRetriedAfterItStops(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        ExecutionCoordinator coordinator = companion.executionCoordinator();
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
                ctx -> new StayTask());
        for (int i = 0; i < 5; i++) {
            coordinator.tick();
        }
        coordinator.stop("test");
        coordinator.tick();
        h.assertValueEqual(coordinator.queue().jobCount(), 0, "the stopped job is gone");
        h.assertFalse(coordinator.retryLast() == false, "the last direct job can be retried");
        h.assertValueEqual(coordinator.queue().jobCount(), 1, "retry queues exactly one job again");
        h.assertValueEqual(coordinator.queue().jobs().getFirst().tool(), "stay", "retry keeps the same tool");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aTaskThatFinishesFarFromTheOwnerStillReportsTheOwnerDistanceDelta(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        // A live owner, because the distance fact is read through the player list.
        ServerPlayer owner =
                GameTestSupport.onlinePlayer(h, "far", 2, 2, 2, true).player();
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        ExecutionCoordinator coordinator = companion.executionCoordinator();
        coordinator.resume();
        RecordingSink events = new RecordingSink();
        coordinator.setEventSink(events);

        var before = OutcomeFacts.snapshot(companion);
        h.assertTrue(before.ownerDistance() >= 0, "the online owner must have a distance: " + before.ownerDistance());

        // The owner walks ~20 blocks away while the companion stays put, and the queued stay still ends by itself.
        Vec3 far = h.absoluteVec(new Vec3(15, 2, 15));
        owner.teleportTo(far.x, far.y, far.z);
        coordinator.enqueue(stayStep(owner.getUUID()), ctx -> new StayTask(40));
        for (int i = 0; i < 80 && coordinator.queue().jobCount() > 0; i++) coordinator.tick();
        h.assertValueEqual(coordinator.queue().jobCount(), 0, "the short stay finishes on its own");
        h.assertTrue(events.finished.contains("stay:SUCCESS"), "the finish is reported: " + events.finished);

        var after = OutcomeFacts.snapshot(companion);
        h.assertTrue(
                after.ownerDistance() - before.ownerDistance() >= 8,
                "the owner is far away by the end: " + before.ownerDistance() + " -> " + after.ownerDistance());
        var outcome = OutcomeChecker.check(before, after, true, List.of(), new CompanionGuardFacts(companion));
        h.assertTrue(
                outcome.factsLine().contains("owner " + after.ownerDistance() + " blocks away"),
                "the finished task reports the owner distance: " + outcome.factsLine());
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void anOwnerStopDoesNotProduceATaskFinishedEvent(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        ExecutionCoordinator coordinator = companion.executionCoordinator();
        coordinator.resume();
        RecordingSink events = new RecordingSink();
        coordinator.setEventSink(events);
        coordinator.enqueue(stayStep(owner.getUUID()), ctx -> new StayTask(200));
        for (int i = 0; i < 5; i++) coordinator.tick();
        h.assertTrue(coordinator.current().isPresent(), "the queued stay runs before the stop");
        h.assertTrue(events.finished.isEmpty(), "nothing finished before the stop: " + events.finished);

        coordinator.stop("owner asked");
        coordinator.tick();

        h.assertTrue(events.finished.isEmpty(), "an owner stop is a cancellation, not a finish: " + events.finished);
        h.assertTrue(
                events.cancelled.contains("stay:owner asked"),
                "the stop reaches the brain as a cancellation: " + events.cancelled);
        h.succeed();
    }

    @GameTest(maxTicks = 300)
    public void aCancelledQueuedJobCanBeRetriedAndRunsToCompletion(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        ExecutionCoordinator coordinator = companion.executionCoordinator();
        coordinator.resume();
        RecordingSink events = new RecordingSink();
        coordinator.setEventSink(events);
        coordinator.enqueue(stayStep(owner.getUUID()), ctx -> new StayTask(15));
        for (int i = 0; i < 4; i++) coordinator.tick();
        coordinator.stop("owner asked");
        coordinator.tick();
        h.assertValueEqual(coordinator.queue().jobCount(), 0, "the stopped job is gone");

        h.assertTrue(coordinator.retryLast(), "a cancelled direct job can be retried");
        h.assertValueEqual(coordinator.queue().jobCount(), 1, "retry queues the job again");
        h.assertValueEqual(coordinator.queue().jobs().getFirst().tool(), "stay", "the retried job is the same tool");
        long retried = coordinator.queue().jobs().getFirst().id();

        for (int i = 0; i < 80 && coordinator.queue().job(retried).isPresent(); i++) coordinator.tick();

        h.assertTrue(coordinator.queue().job(retried).isEmpty(), "the retried stay leaves the queue when it ends");
        h.assertValueEqual(coordinator.queue().jobCount(), 0, "the completed retry leaves no job behind");
        h.assertTrue(events.finished.contains("stay:SUCCESS"), "the retry finishes on its own: " + events.finished);
        h.succeed();
    }
}
