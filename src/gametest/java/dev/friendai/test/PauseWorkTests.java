package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.HoldKey;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueueHold;
import dev.friendai.execution.queue.QueueLimits;
import dev.friendai.execution.queue.QueueSave;
import dev.friendai.execution.queue.QueueText;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.features.interaction.minecraft.task.WaitTask;
import dev.friendai.features.navigation.minecraft.task.GoToTask;
import dev.friendai.features.navigation.minecraft.task.StayTask;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.queue.CompanionGuardFacts;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** The owner's pause, logout and stop hold the runner the same way, and only the owner's own pause outlives a restart. */
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

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 600)
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

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 100)
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

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 600)
    public void aLogoutHaltsAWalkingBody(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
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
                if (last[0] != null && now.distanceTo(last[0]) > 0.01) moves[0]++;
                last[0] = now;
                if (moves[0] < 10) return;
                GameTestSupport.runtime(h).sessions().onOwnerLeft(owner.getUUID());
                h.assertTrue(coordinator.suspended(), "the owner leaving must suspend the runner");
                held[0] = now;
                return;
            }
            if (pausedTicks[0] < 30) {
                h.assertTrue(
                        now.distanceTo(held[0]) < 0.05,
                        "a logged-out companion kept walking " + now.distanceTo(held[0]) + " blocks");
                h.assertValueEqual(coordinator.holdReason(), "owner_away", "the panel names the absent owner");
                pausedTicks[0]++;
                return;
            }
            if (pausedTicks[0] == 30) {
                GameTestSupport.runtime(h).sessions().onOwnerJoined(owner.getUUID());
                h.assertTrue(!coordinator.suspended(), "the owner returning must resume the runner");
                pausedTicks[0]++;
                return;
            }
            h.assertTrue(
                    now.distanceTo(target) <= 2.0,
                    "a resumed companion did not finish walking: " + now + " vs " + target);
        });
    }

    @GameTest(maxTicks = 40)
    public void aRestoredStandaloneTaskWaitsForItsOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        // An owner that is not in the game: what a save gives back on a server whose owner has not logged in yet.
        companion.setOwner(UUID.randomUUID());
        CountingTask task = new CountingTask();
        var coordinator = companion.executionCoordinator();
        coordinator.suspend();
        coordinator.installRestoredState(
                new QueueSave.Decoded(new JobQueue(QueueLimits.DEFAULTS), 0), Map.of(), Map.of(), task);

        // The offline free-time policy resumes the runner without meaning to start owner work.
        coordinator.resume();

        h.assertTrue(!task.started, "restored owner work started while its owner was away");
        h.assertTrue(coordinator.current().isEmpty(), "restored owner work took the runner");
        h.assertValueEqual(coordinator.holdReason(), "owner_away", "the panel names the absent owner");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aStopDiscardsAStandaloneCheckpointThatNeverStarted(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        CountingTask task = new CountingTask();
        var coordinator = companion.executionCoordinator();
        coordinator.suspend();
        coordinator.installRestoredState(
                new QueueSave.Decoded(new JobQueue(QueueLimits.DEFAULTS), 0), Map.of(), Map.of(), task);
        h.assertTrue(!task.started, "a suspended restore must not start");

        coordinator.stop("the owner stopped it");
        coordinator.resume();

        h.assertTrue(!task.started, "a stop must drop the restored work it never started");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aRestoredJobStillStartsOnJoin(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        Job job = queue.addDirect(
                new QueuedStep("wait", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of()));
        queue.markRunning(job.id());
        var coordinator = companion.executionCoordinator();
        coordinator.suspend();
        coordinator.installRestoredState(
                new QueueSave.Decoded(queue, job.id()),
                Map.of(job.id(), context -> new WaitTask(20)),
                Map.of(),
                new WaitTask(20));
        h.assertTrue(coordinator.current().isEmpty(), "a suspended restore waits");

        coordinator.resume();

        h.assertValueEqual(
                coordinator.current().map(Task::type).orElse("none"),
                "wait",
                "a restored queue job starts when the owner returns");
        coordinator.stop("test over");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aSavedPauseSurvivesRestartAndJoin(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var persistence = GameTestSupport.runtime(h).taskPersistence();
        var coordinator = companion.executionCoordinator();
        coordinator.resume();
        coordinator.setManualPause(true);

        var saved = persistence.capture(coordinator).orElseThrow();
        h.assertTrue(saved.manualPause(), "the save must carry the owner's pause");
        // A fresh body: the runner of a reloaded companion starts unpaused before the save is read.
        coordinator.setManualPause(false);
        persistence.restore(companion, saved);

        h.assertTrue(coordinator.manualPause(), "the saved pause must come back with the work");
        h.assertTrue(coordinator.suspended(), "a paused companion comes back held");
        h.assertValueEqual(coordinator.holdReason(), "manual_pause", "the panel names the owner's pause");

        coordinator.setManualPause(false);
        h.assertTrue(!coordinator.suspended(), "releasing the pause resumes the runner");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void theHoldReasonNamesTheOwnerPauseFirst(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var coordinator = companion.executionCoordinator();
        coordinator.resume();
        h.assertValueEqual(coordinator.holdReason(), "", "work that may run has no reason");

        var combat = coordinator.acquireCombatHold();
        h.assertValueEqual(coordinator.holdReason(), "combat", "a temporary hold names itself");

        coordinator.setManualPause(true);
        h.assertValueEqual(coordinator.holdReason(), "manual_pause", "the owner's pause outranks the temporary hold");

        coordinator.setManualPause(false);
        combat.close();
        h.assertValueEqual(coordinator.holdReason(), "", "releasing everything clears the reason");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void pausingTheRunningActionStopsItAtItsNextTickAndAnotherPlanProceeds(GameTestHelper h) {
        GameTestSupport.platformAt(h, 700, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 702, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 702, 2);
        companion.setOwner(owner.getUUID());
        var coordinator = companion.executionCoordinator();
        int[] building = {0};
        int[] other = {0};
        long plan = coordinator
                .queue()
                .addPlan(
                        "build",
                        List.of(new QueuedStep(
                                "stay",
                                new JsonObject(),
                                null,
                                List.of(),
                                GuardAction.WAIT,
                                FailurePolicy.ASK,
                                1,
                                List.of())),
                        PlanOptions.DEFAULT)
                .planId();
        long job = coordinator.queue().jobsOfPlan(plan).getFirst().id();
        coordinator.registerFactory(job, context -> countingTask("stay", building));
        coordinator.wakeQueue();
        coordinator.tick();
        h.assertTrue(coordinator.runningJobIs(job), "the plan's action starts");
        int before = building[0];

        coordinator.queue().setHold(HoldKey.plan(plan), QueueHold.pause("owner"));
        coordinator.tick();
        coordinator.tick();
        h.assertValueEqual(building[0], before, "a paused action stops at its tick boundary");
        h.assertTrue(
                coordinator.queue().job(job).orElseThrow().state() == dev.friendai.execution.queue.JobState.RUNNING,
                "the paused action keeps its place in the plan");
        h.assertTrue(
                QueueText.show(coordinator.queue(), new CompanionGuardFacts(companion), null, 10, false)
                        .contains("paused (owner)"),
                "the queue text says why it stands still");

        // Another plan with its own hold-free work runs while the paused plan keeps its place.
        long otherPlan = coordinator
                .queue()
                .addPlan(
                        "tidy",
                        List.of(new QueuedStep(
                                "tidy",
                                new JsonObject(),
                                null,
                                List.of(),
                                GuardAction.WAIT,
                                FailurePolicy.ASK,
                                1,
                                List.of())),
                        PlanOptions.DEFAULT)
                .planId();
        long otherJob = coordinator.queue().jobsOfPlan(otherPlan).getFirst().id();
        coordinator.registerFactory(otherJob, context -> finishingTask("tidy", other));
        coordinator.wakeQueue();
        for (int i = 0; i < 5 && other[0] == 0; i++) coordinator.tick();
        h.assertTrue(other[0] > 0, "an unrelated ready plan proceeds while the held one waits");
        h.assertValueEqual(building[0], before, "the held action stays stopped");

        coordinator.queue().release(HoldKey.plan(plan));
        coordinator.wakeQueue();
        for (int i = 0; i < 6 && building[0] == before; i++) coordinator.tick();
        h.assertTrue(building[0] > before, "releasing the hold resumes the same action");
        coordinator.stop("test over");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aDeferredConditionalActionIsNeitherRunNorSkippedBeforeItsWake(GameTestHelper h) {
        GameTestSupport.platformAt(h, 900, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 902, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 902, 2);
        companion.setOwner(owner.getUUID());
        var coordinator = companion.executionCoordinator();
        int[] runs = {0};
        long plan = coordinator.enqueuePlan(
                "guarded",
                new PlanDefinition(List.of(new PlanNode.Action(new QueuedStep(
                        "skip_me",
                        new JsonObject(),
                        null,
                        List.of(new dev.friendai.execution.queue.Guard.HasItem("minecraft:diamond", 1)),
                        GuardAction.SKIP,
                        FailurePolicy.ASK,
                        1,
                        List.of())))),
                PlanOptions.DEFAULT,
                Map.of("skip_me", (context, step) -> countingTask("skip_me", runs)));
        long job = coordinator.queue().jobsOfPlan(plan).getFirst().id();
        coordinator
                .queue()
                .setHold(
                        HoldKey.job(job),
                        QueueHold.defer("later", coordinator.queue().clock() + 30, null));
        coordinator.wakeQueue();
        for (int i = 0; i < 10; i++) coordinator.tick();
        h.assertValueEqual(
                coordinator.queue().job(job).orElseThrow().state(),
                dev.friendai.execution.queue.JobState.PENDING,
                "a deferred action is not skipped by its guard before its wake");
        h.assertValueEqual(runs[0], 0, "and it did not run either");
        h.assertTrue(coordinator.queue().plan(plan).isPresent(), "the plan still stands");

        for (int i = 0; i < 40; i++) coordinator.tick();
        // Past its wake the action is evaluated: it runs, is skipped, or the skip finished the whole plan.
        h.assertTrue(
                runs[0] > 0
                        || coordinator.queue().job(job).map(Job::state).orElse(null)
                                == dev.friendai.execution.queue.JobState.SKIPPED
                        || coordinator.queue().jobsOfPlan(plan).isEmpty(),
                "once its wake passes the action is evaluated: "
                        + coordinator.queue().jobs());
        h.succeed();
    }

    /** Counts its ticks and finishes after the first one, so a preempting job hands the body back. */
    private static Task finishingTask(String type, int[] ticks) {
        return new Task() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void start(TaskContext context) {}

            @Override
            public TaskStatus tick(TaskContext context) {
                ticks[0]++;
                return TaskStatus.SUCCESS;
            }

            @Override
            public void stop(TaskContext context) {}

            @Override
            public String describe() {
                return type;
            }

            @Override
            public String resultSummary() {
                return "done";
            }
        };
    }

    private static Task countingTask(String type, int[] ticks) {
        return new Task() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void start(TaskContext context) {}

            @Override
            public TaskStatus tick(TaskContext context) {
                ticks[0]++;
                return TaskStatus.RUNNING;
            }

            @Override
            public void stop(TaskContext context) {}

            @Override
            public String describe() {
                return type;
            }

            @Override
            public String resultSummary() {
                return "";
            }
        };
    }

    /** A restored task that records whether anything ever started it. */
    private static final class CountingTask implements Task {
        boolean started;

        @Override
        public String type() {
            return "counting";
        }

        @Override
        public void start(TaskContext context) {
            started = true;
        }

        @Override
        public TaskStatus tick(TaskContext context) {
            return TaskStatus.RUNNING;
        }

        @Override
        public void stop(TaskContext context) {}

        @Override
        public String describe() {
            return "counting task";
        }

        @Override
        public String resultSummary() {
            return "counted";
        }
    }
}
