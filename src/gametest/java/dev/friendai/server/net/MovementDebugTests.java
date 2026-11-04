package dev.friendai.server.net;

import dev.friendai.debug.DebugAction;
import dev.friendai.debug.MovementDebug;
import dev.friendai.debug.WorkCell;
import dev.friendai.debug.WorkKind;
import dev.friendai.debug.WorkSet;
import dev.friendai.debug.WorkStatus;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.mining.excavation.ExcavationGeometry;
import dev.friendai.features.mining.excavation.ExcavationSpec;
import dev.friendai.features.mining.minecraft.task.ExcavateTask;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Server assembly checks for task-owned movement debug work sets. */
public final class MovementDebugTests {
    private static final WorkSet WORK = new WorkSet(
            "debug sample",
            WorkKind.BLOCKS,
            2,
            0,
            1,
            1,
            0,
            false,
            List.of(
                    new WorkCell(4, 2, 4, DebugAction.BREAK, WorkStatus.NEXT, "sample"),
                    new WorkCell(5, 2, 4, DebugAction.BREAK, WorkStatus.PENDING, "sample")));

    @GameTest
    public void activeTaskWorkSetsAreIncluded(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.executionCoordinator().start(new SnapshotTask(), "debug_snapshot");
        MovementDebug view = MovementDebugStream.build(
                ServerRuntimeRegistry.getOrCreate(helper.getLevel().getServer()), companion);
        helper.assertValueEqual(view.workSets().size(), 1, "active work-set count");
        helper.assertValueEqual(view.workSets().getFirst().total(), 2, "whole work-set total");
        helper.assertTrue(
                view.workSets().getFirst().cells().stream().anyMatch(cell -> cell.status() == WorkStatus.NEXT),
                "next work cell should be sent");
        helper.succeed();
    }

    @GameTest
    public void aThrowingTaskHookDoesNotBreakTheView(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.executionCoordinator().start(new ThrowingTask(), "debug_throwing");
        MovementDebug view = MovementDebugStream.build(
                ServerRuntimeRegistry.getOrCreate(helper.getLevel().getServer()), companion);
        helper.assertTrue(
                view.lines().stream().anyMatch(line -> line.startsWith("task: ")),
                "the task line survives a failing hook");
        helper.assertTrue(
                view.lines().stream().anyMatch(line -> line.contains("debug: work sets unavailable")),
                "the failing hook is reported");
        helper.succeed();
    }

    @GameTest
    public void excavationPublishesItsPlannedCells(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExcavationSpec spec = ExcavationSpec.of(
                        ExcavationGeometry.box(4, 2, 4, 5, 2, 4).orElseThrow(),
                        2,
                        null,
                        0,
                        false,
                        false,
                        false,
                        true,
                        0)
                .orElseThrow();
        companion.executionCoordinator().start(new ExcavateTask(spec), "excavate");
        MovementDebug view = MovementDebugStream.build(
                ServerRuntimeRegistry.getOrCreate(helper.getLevel().getServer()), companion);
        helper.assertTrue(
                view.workSets().stream().anyMatch(set -> set.kind() == WorkKind.BLOCKS),
                "excavation publishes a block work set");
        helper.assertValueEqual(
                view.workSets().stream().mapToInt(WorkSet::total).sum(), 2, "the whole region is counted");
        helper.assertTrue(!view.targets().isEmpty(), "excavation publishes its current target");
        helper.succeed();
    }

    private static final class ThrowingTask implements Task {
        @Override
        public String type() {
            return "debug_throwing";
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
            return "debug throwing test";
        }

        @Override
        public String resultSummary() {
            return "";
        }

        @Override
        public List<WorkSet> debugWorkSets() {
            throw new IllegalStateException("broken work-set hook");
        }
    }

    private static final class SnapshotTask implements Task {
        @Override
        public String type() {
            return "debug_snapshot";
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
            return "debug snapshot test";
        }

        @Override
        public String resultSummary() {
            return "";
        }

        @Override
        public List<WorkSet> debugWorkSets() {
            return List.of(WORK);
        }
    }
}
