package dev.friendai.net;

import dev.friendai.debug.DebugAction;
import dev.friendai.debug.MovementDebug;
import dev.friendai.debug.WorkCell;
import dev.friendai.debug.WorkKind;
import dev.friendai.debug.WorkSet;
import dev.friendai.debug.WorkStatus;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
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
        MovementDebug view = MovementDebugStream.build(companion);
        helper.assertValueEqual(view.workSets().size(), 1, "active work-set count");
        helper.assertValueEqual(view.workSets().getFirst().total(), 2, "whole work-set total");
        helper.assertTrue(
                view.workSets().getFirst().cells().stream().anyMatch(cell -> cell.status() == WorkStatus.NEXT),
                "next work cell should be sent");
        helper.succeed();
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
