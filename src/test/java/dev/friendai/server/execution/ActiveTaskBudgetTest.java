import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.server.execution.ActiveTaskController;
import dev.friendai.server.execution.TaskStartContext;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import org.junit.jupiter.api.Test;

class ActiveTaskBudgetTest {
    @Test
    void aFreshActivationStartsWithItsFullBudget() {
        ActiveTaskController controller = new ActiveTaskController(() -> 1);

        controller.start(new SpinningTask(), start("mine_blocks"));

        assertEquals(0, controller.elapsedActiveTicks());
        assertEquals(1, controller.activeTimeoutSec());
    }

    @Test
    void aRestoredActivationKeepsTheTimeItAlreadySpent() {
        ActiveTaskController controller = new ActiveTaskController(() -> 60);

        controller.start(new SpinningTask(), start("mine_blocks"), 900, 60);
        controller.tick(context());

        assertEquals(901, controller.elapsedActiveTicks(), "one spent tick sits on top of the restored 900");
        assertEquals(60, controller.activeTimeoutSec());
    }

    @Test
    void aRestoredTaskTimesOutAfterItsRemainingTicksOnly() {
        ActiveTaskController controller = new ActiveTaskController(() -> 1);

        // 19 ticks of a 20-tick budget were spent before the save: one tick of real work remains.
        controller.start(new SpinningTask(), start("mine_blocks"), 19, 1);
        var first = controller.tick(context());
        assertTrue(first.isEmpty(), "the first tick still fits in the remaining budget");

        var second = controller.tick(context()).orElseThrow();
        assertEquals(TaskStatus.FAILED, second.status());
        assertEquals("timed out", second.summary(), "a restored task keeps the original hard deadline");
    }

    @Test
    void aRestoredTimeoutIsCappedAtTheBound() {
        ActiveTaskController controller = new ActiveTaskController(() -> 60);

        controller.start(
                new SpinningTask(), start("mine_blocks"), 10, ActiveTaskController.MAX_RESTORED_TIMEOUT_SEC + 1);

        assertEquals(
                ActiveTaskController.MAX_RESTORED_TIMEOUT_SEC,
                controller.activeTimeoutSec(),
                "a save cannot extend an activation beyond the bounded maximum");
    }

    @Test
    void anElapsedBudgetBeyondTheLimitIsClampedInsteadOfWrapping() {
        ActiveTaskController controller = new ActiveTaskController(() -> 60);

        controller.start(
                new SpinningTask(), start("mine_blocks"), ActiveTaskController.MAX_RESTORED_ELAPSED_TICKS + 500, 60);

        assertEquals(
                ActiveTaskController.MAX_RESTORED_ELAPSED_TICKS,
                controller.elapsedActiveTicks(),
                "a hostile save cannot claim more than the bounded maximum");
    }

    @Test
    void aFrozenActivationReportsTheWorkItDidBeforeTheInterrupt() {
        ActiveTaskController controller = new ActiveTaskController(() -> 60);
        controller.start(new SpinningTask(), start("mine_blocks"));
        controller.tick(context());
        controller.tick(context());

        controller.freezeCurrent();
        assertEquals(2, controller.elapsedActiveTicks(), "a freeze saves the work done before the interrupt");
        assertEquals(60, controller.activeTimeoutSec());

        controller.resumeFrozen(controller.takeFrozen().orElseThrow());
        assertEquals(2, controller.elapsedActiveTicks(), "the interrupt itself adds no active time");
        controller.tick(context());
        assertEquals(3, controller.elapsedActiveTicks(), "work continues from where the freeze left it");
    }

    private static TaskContext context() {
        return new TaskContext(null, null, null, null, null);
    }

    private static TaskStartContext start(String tool) {
        return new TaskStartContext(
                tool, null, null, true, false, false, 1, 0, 0, false, ServerSettings.DEFAULTS, context(), null, "test");
    }

    private static final class SpinningTask implements Task {
        @Override
        public String type() {
            return "spinning_task";
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
            return "spinning";
        }

        @Override
        public String resultSummary() {
            return "";
        }
    }
}
