package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.queue.FailurePolicy;
import dev.friendai.queue.GuardAction;
import dev.friendai.queue.PlanOptions;
import dev.friendai.queue.QueuedStep;
import dev.friendai.task.TaskRunner;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.StayTask;
import dev.friendai.task.plan.StepFactory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Restart handling for queued trading authority markers. */
public final class TradingAuthorityTests {
    private static final String SCOPE = "00000000-0000-0000-0000-000000000123";

    private static final class UnrestorableTask implements Task {
        @Override public String type() { return "obtain_item"; }
        @Override public void start(TaskContext context) {}
        @Override public TaskStatus tick(TaskContext context) { return TaskStatus.RUNNING; }
        @Override public void stop(TaskContext context) {}
        @Override public String describe() { return "unrestorable spending task"; }
        @Override public String resultSummary() { return "unrestorable"; }
    }

    @GameTest
    public void restartCancelsActiveAndPendingSpendingButKeepsNonSpendingWork(GameTestHelper h) {
        CompanionEntity original = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        TaskRunner before = original.taskRunner();
        long active = before.enqueue(step("obtain_item", true), ctx -> new UnrestorableTask());
        long pending = before.enqueue(step("trade_item", true), ctx -> new StayTask());
        long ordinary = before.enqueue(step("stay", false), ctx -> new StayTask());
        String saved = before.savedQueue().orElseThrow();

        CompanionEntity restored = h.spawn(FriendaiEntities.COMPANION, 5, 2, 5);
        TaskRunner after = restored.taskRunner();
        int[] factoryCalls = {0};
        after.restoreQueue(dev.friendai.queue.QueueSave.decode(saved, dev.friendai.queue.QueueLimits.DEFAULTS).orElseThrow(),
                Map.of(active, countingFactory(factoryCalls), pending, countingFactory(factoryCalls),
                        ordinary, countingFactory(factoryCalls)));

        h.assertTrue(after.queue().job(active).orElseThrow().state() == dev.friendai.queue.JobState.CANCELLED,
                "active spending task was not cancelled before restore");
        h.assertTrue(after.queue().job(pending).orElseThrow().state() == dev.friendai.queue.JobState.CANCELLED,
                "pending spending task was not cancelled before restore");
        h.assertTrue(factoryCalls[0] == 0, "expired spending factories ran during restore");
        after.tick();
        h.assertTrue(factoryCalls[0] == 1 && after.current().map(task -> task.type().equals("stay")).orElse(false),
                "nonspending saved work did not remain runnable");
        h.succeed();
    }

    @GameTest
    public void restartDiscardsAPlanContainingAnExpiredSpendingStep(GameTestHelper h) {
        CompanionEntity original = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        TaskRunner before = original.taskRunner();
        int[] factoryCalls = {0};
        var spending = step("obtain_item", true);
        var ordinary = step("stay", false);
        long plan = before.enqueuePlan("spend only with owner", List.of(spending, ordinary),
                List.of(countingFactory(factoryCalls), countingFactory(factoryCalls)), PlanOptions.DEFAULT);
        String saved = before.savedQueue().orElseThrow();
        int factoryCallsBeforeRestore = factoryCalls[0];

        CompanionEntity restored = h.spawn(FriendaiEntities.COMPANION, 5, 2, 5);
        TaskRunner after = restored.taskRunner();
        var decoded = dev.friendai.queue.QueueSave.decode(saved, dev.friendai.queue.QueueLimits.DEFAULTS).orElseThrow();
        var factories = new java.util.HashMap<Long, StepFactory>();
        decoded.queue().jobsOfPlan(plan).forEach(job -> factories.put(job.id(), countingFactory(factoryCalls)));
        after.restoreQueue(decoded, factories);

        h.assertTrue(after.queue().plan(plan).isEmpty(), "plan containing an expired spending step was restored");
        h.assertTrue(factoryCalls[0] == factoryCallsBeforeRestore,
                "a restored plan factory started despite an expired scope");
        h.succeed();
    }

    private static QueuedStep step(String tool, boolean spending) {
        JsonObject args = new JsonObject();
        if (spending) args.addProperty("_trade_scope", SCOPE);
        return new QueuedStep(tool, args, null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
    }

    private static StepFactory countingFactory(int[] calls) {
        return ctx -> {
            calls[0]++;
            return new StayTask();
        };
    }
}
