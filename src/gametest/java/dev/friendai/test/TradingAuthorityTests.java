package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.queue.FailurePolicy;
import dev.friendai.queue.GuardAction;
import dev.friendai.queue.JobState;
import dev.friendai.queue.PlanOptions;
import dev.friendai.queue.QueuedStep;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.StayTask;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Restart handling for queued trading authority markers. */
public final class TradingAuthorityTests {
    private static final String SCOPE = "00000000-0000-0000-0000-000000000123";

    private static final class UnrestorableTask implements Task {
        @Override
        public String type() {
            return "obtain_item";
        }

        @Override
        public void start(TaskContext context) {}

        @Override
        public TaskStatus tick(TaskContext context) {
            return TaskStatus.RUNNING;
        }

        @Override
        public void stop(TaskContext context) {}

        @Override
        public String describe() {
            return "unrestorable spending task";
        }

        @Override
        public String resultSummary() {
            return "unrestorable";
        }
    }

    @GameTest
    public void restartCancelsSpendingAndKeepsOwnerWork(GameTestHelper h) {
        UUID owner = UUID.randomUUID();
        CompanionEntity original = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        original.setOwner(owner);
        ExecutionCoordinator before = original.executionCoordinator();
        long active = before.enqueue(step("obtain_item", true, owner), ctx -> new UnrestorableTask());
        long pending = before.enqueue(step("trade_item", true, owner), ctx -> new StayTask());
        long ordinary = before.enqueue(step("stay", false, owner), ctx -> new StayTask());
        var persistence = FriendaiServerState.get(h.getLevel().getServer()).taskPersistence();
        var saved = persistence
                .decode(persistence.encode(persistence.capture(before).orElseThrow()))
                .orElseThrow();

        CompanionEntity restored = h.spawn(FriendaiEntities.COMPANION, 5, 2, 5);
        restored.setOwner(owner);
        ExecutionCoordinator after = restored.executionCoordinator();
        persistence.restore(restored, saved);

        h.assertTrue(
                after.queue().job(active).orElseThrow().state() == JobState.CANCELLED,
                "active spending task was not cancelled before restore");
        h.assertTrue(
                after.queue().job(pending).orElseThrow().state() == JobState.CANCELLED,
                "pending spending task was not cancelled before restore");
        h.assertTrue(
                after.queue().job(ordinary).orElseThrow().state() == JobState.PENDING,
                "ordinary owner work should remain queued");
        after.tick();
        h.assertTrue(
                after.current().map(task -> task.type().equals("stay")).orElse(false),
                "nonspending saved work did not remain runnable");
        h.succeed();
    }

    @GameTest
    public void restartDiscardsAPlanContainingAnExpiredSpendingStep(GameTestHelper h) {
        UUID owner = UUID.randomUUID();
        CompanionEntity original = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        original.setOwner(owner);
        ExecutionCoordinator before = original.executionCoordinator();
        var spending = step("obtain_item", true, owner);
        var ordinary = step("stay", false, owner);
        long plan = before.enqueuePlan(
                "spend only with owner",
                List.of(spending, ordinary),
                List.of(context -> new StayTask(), context -> new StayTask()),
                PlanOptions.DEFAULT);
        var persistence = FriendaiServerState.get(h.getLevel().getServer()).taskPersistence();
        var saved = persistence
                .decode(persistence.encode(persistence.capture(before).orElseThrow()))
                .orElseThrow();

        CompanionEntity restored = h.spawn(FriendaiEntities.COMPANION, 5, 2, 5);
        restored.setOwner(owner);
        ExecutionCoordinator after = restored.executionCoordinator();
        persistence.restore(restored, saved);

        h.assertTrue(after.queue().plan(plan).isEmpty(), "plan containing an expired spending step was restored");
        h.assertTrue(after.current().isEmpty(), "a factory for the rejected plan started during restore");
        h.succeed();
    }

    @GameTest
    public void restartRejectsWorkWhoseSavedActorIsMissing(GameTestHelper h) {
        UUID owner = UUID.randomUUID();
        CompanionEntity original = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        original.setOwner(owner);
        long job = original.executionCoordinator().enqueue(step("stay", false, null), ctx -> new StayTask());
        var persistence = FriendaiServerState.get(h.getLevel().getServer()).taskPersistence();
        var saved = persistence
                .decode(persistence.encode(
                        persistence.capture(original.executionCoordinator()).orElseThrow()))
                .orElseThrow();

        CompanionEntity restored = h.spawn(FriendaiEntities.COMPANION, 5, 2, 5);
        restored.setOwner(owner);
        persistence.restore(restored, saved);

        h.assertTrue(
                restored.executionCoordinator().queue().job(job).orElseThrow().state() == JobState.CANCELLED,
                "work without an initiating actor must not resume");
        h.assertTrue(
                restored.executionCoordinator().current().isEmpty(), "missing actor's task must not be instantiated");
        h.succeed();
    }

    private static QueuedStep step(String tool, boolean spending, UUID principal) {
        JsonObject args = new JsonObject();
        if (spending) args.addProperty("_trade_scope", SCOPE);
        return new QueuedStep(
                tool, args, null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of(), principal, false);
    }
}
