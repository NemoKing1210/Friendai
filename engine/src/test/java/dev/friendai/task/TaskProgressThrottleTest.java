package dev.friendai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TaskProgressThrottleTest {
    private static final String DETAIL = "working through the wall";

    @Test
    void phaseChangesAreTheOnlyPhaseTrigger() {
        assertTrue(TaskProgressThrottle.deservesEvent(null, TaskPhase.STARTED));
        assertTrue(TaskProgressThrottle.deservesEvent(TaskPhase.STARTED, TaskPhase.PROGRESS));
        assertTrue(TaskProgressThrottle.deservesEvent(TaskPhase.PROGRESS, TaskPhase.WAITING));
        assertTrue(TaskProgressThrottle.deservesEvent(TaskPhase.WAITING, TaskPhase.PROGRESS));
        assertFalse(TaskProgressThrottle.deservesEvent(TaskPhase.PROGRESS, TaskPhase.PROGRESS));
        assertFalse(TaskProgressThrottle.deservesEvent(TaskPhase.WAITING, TaskPhase.WAITING));
        assertFalse(TaskProgressThrottle.deservesEvent(TaskPhase.FINISHED, TaskPhase.FINISHED));
        assertFalse(TaskProgressThrottle.deservesEvent(TaskPhase.PROGRESS, null));
    }

    @Test
    void theFirstObservationStartsTheLifecycle() {
        TaskProgressThrottle throttle = new TaskProgressThrottle();
        TaskProgress event = observe(throttle, TaskPhase.STARTED, -1, ProgressWatch.UNKNOWN, 0);
        assertNotNull(event);
        assertEquals(1, event.taskId());
        assertEquals("mine_blocks", event.tool());
        assertEquals(TaskPhase.STARTED, event.phase());
        assertEquals(DETAIL, event.detail());
    }

    @Test
    void aRepeatedPhaseStaysQuiet() {
        TaskProgressThrottle throttle = new TaskProgressThrottle();
        assertNotNull(observe(throttle, TaskPhase.STARTED, -1, ProgressWatch.UNKNOWN, 0));
        assertNull(observe(throttle, TaskPhase.STARTED, -1, ProgressWatch.UNKNOWN, 1));
        assertNull(observe(throttle, TaskPhase.STARTED, -1, ProgressWatch.UNKNOWN, 500));
    }

    @Test
    void aPhaseChangeIsReportedAtOnceWithoutWaitingForTheInterval() {
        TaskProgressThrottle throttle = new TaskProgressThrottle();
        assertNotNull(observe(throttle, TaskPhase.STARTED, -1, ProgressWatch.UNKNOWN, 0));
        assertNotNull(observe(throttle, TaskPhase.WAITING, -1, ProgressWatch.UNKNOWN, 1));
        assertNotNull(observe(throttle, TaskPhase.PROGRESS, -1, 5, 2));
        assertNotNull(observe(throttle, TaskPhase.RETRYING, -1, 5, 3));
        assertNotNull(observe(throttle, TaskPhase.FINISHED, -1, 5, 4));
    }

    @Test
    void progressIsLimitedToOneEventPerHundredActiveTicks() {
        TaskProgressThrottle throttle = new TaskProgressThrottle();
        assertNotNull(observe(throttle, TaskPhase.STARTED, -1, ProgressWatch.UNKNOWN, 0));
        assertNotNull(observe(throttle, TaskPhase.PROGRESS, -1, 1, 100));
        assertNull(observe(throttle, TaskPhase.PROGRESS, -1, 2, 150));
        assertNull(observe(throttle, TaskPhase.PROGRESS, -1, 3, 199));
        assertNotNull(observe(throttle, TaskPhase.PROGRESS, -1, 4, 200));
    }

    @Test
    void aQuietTickDoesNotRestartTheInterval() {
        TaskProgressThrottle throttle = new TaskProgressThrottle();
        assertNotNull(observe(throttle, TaskPhase.STARTED, -1, ProgressWatch.UNKNOWN, 0));
        assertNotNull(observe(throttle, TaskPhase.PROGRESS, -1, 1, 100));
        for (int tick = 101; tick < 200; tick++) {
            assertNull(observe(throttle, TaskPhase.PROGRESS, -1, 1 + tick, tick));
        }
        assertNotNull(observe(throttle, TaskPhase.PROGRESS, -1, 500, 200));
    }

    @Test
    void aProgressEventNeedsAMovedStamp() {
        TaskProgressThrottle throttle = new TaskProgressThrottle();
        assertNotNull(observe(throttle, TaskPhase.STARTED, -1, ProgressWatch.UNKNOWN, 0));
        assertNotNull(observe(throttle, TaskPhase.PROGRESS, -1, 1, 100));
        assertNull(observe(throttle, TaskPhase.PROGRESS, -1, 1, 400));
    }

    @Test
    void anUnknownStampNeverClaimsNewProgress() {
        TaskProgressThrottle throttle = new TaskProgressThrottle();
        assertNotNull(observe(throttle, TaskPhase.STARTED, -1, ProgressWatch.UNKNOWN, 0));
        TaskProgress first = observe(throttle, TaskPhase.PROGRESS, -1, ProgressWatch.UNKNOWN, 1);
        assertNotNull(first);
        assertEquals(-1, first.percent());
        assertFalse(first.percentKnown());
        assertNull(observe(throttle, TaskPhase.PROGRESS, -1, ProgressWatch.UNKNOWN, 500));
    }

    @Test
    void aMeasuredPercentTravelsWithTheEvent() {
        TaskProgressThrottle throttle = new TaskProgressThrottle();
        assertNotNull(observe(throttle, TaskPhase.STARTED, -1, ProgressWatch.UNKNOWN, 0));
        TaskProgress event = observe(throttle, TaskPhase.PROGRESS, 140, ProgressWatch.UNKNOWN, 1);
        assertNotNull(event);
        assertEquals(100, event.percent());
        assertTrue(event.percentKnown());
    }

    @Test
    void percentIsClampedBeforeItIsCompared() {
        TaskProgressThrottle throttle = new TaskProgressThrottle();
        assertNotNull(observe(throttle, TaskPhase.STARTED, -1, ProgressWatch.UNKNOWN, 0));
        assertNotNull(observe(throttle, TaskPhase.PROGRESS, 140, ProgressWatch.UNKNOWN, 100));
        // 101 and 140 both mean "100%": neither is new evidence, so the milestone does not repeat.
        assertNull(observe(throttle, TaskPhase.PROGRESS, 101, ProgressWatch.UNKNOWN, 400));
    }

    @Test
    void waitingAndRetryingNeverRepeatEvenAfterTheInterval() {
        TaskProgressThrottle throttle = new TaskProgressThrottle();
        assertNotNull(observe(throttle, TaskPhase.STARTED, -1, ProgressWatch.UNKNOWN, 0));
        assertNotNull(observe(throttle, TaskPhase.WAITING, -1, ProgressWatch.UNKNOWN, 10));
        assertNull(observe(throttle, TaskPhase.WAITING, -1, ProgressWatch.UNKNOWN, 400));
        assertNotNull(observe(throttle, TaskPhase.RETRYING, -1, ProgressWatch.UNKNOWN, 401));
        assertNull(observe(throttle, TaskPhase.RETRYING, -1, ProgressWatch.UNKNOWN, 900));
    }

    @Test
    void theFinishIsReportedOnceAndThenSilence() {
        TaskProgressThrottle throttle = new TaskProgressThrottle();
        assertNotNull(observe(throttle, TaskPhase.STARTED, -1, ProgressWatch.UNKNOWN, 0));
        assertNotNull(observe(throttle, TaskPhase.PROGRESS, -1, 1, 100));
        assertNotNull(observe(throttle, TaskPhase.FINISHED, 100, 1, 110));
        assertNull(observe(throttle, TaskPhase.FINISHED, 100, 1, 900));
    }

    @Test
    void aNewTaskIdStartsAFreshLifecycle() {
        TaskProgressThrottle throttle = new TaskProgressThrottle();
        assertNotNull(throttle.observe(4, "go_to", TaskPhase.STARTED, -1, "walking", ProgressWatch.UNKNOWN, 0));
        assertNotNull(throttle.observe(4, "go_to", TaskPhase.WAITING, -1, "waiting", ProgressWatch.UNKNOWN, 1));
        TaskProgress next = throttle.observe(5, "mine_blocks", TaskPhase.STARTED, -1, DETAIL, ProgressWatch.UNKNOWN, 2);
        assertNotNull(next);
        assertEquals(5, next.taskId());
        assertEquals(TaskPhase.STARTED, next.phase());
        assertEquals(TaskPhase.STARTED, throttle.phase());
    }

    @Test
    void resetForgetsTheCurrentLifecycle() {
        TaskProgressThrottle throttle = new TaskProgressThrottle();
        assertNotNull(observe(throttle, TaskPhase.STARTED, -1, ProgressWatch.UNKNOWN, 0));
        assertNull(observe(throttle, TaskPhase.STARTED, -1, ProgressWatch.UNKNOWN, 1));
        throttle.reset();
        assertNull(throttle.phase());
        assertNotNull(observe(throttle, TaskPhase.STARTED, -1, ProgressWatch.UNKNOWN, 2));
    }

    private static TaskProgress observe(
            TaskProgressThrottle throttle, TaskPhase phase, int percent, long stamp, int tick) {
        return throttle.observe(1, "mine_blocks", phase, percent, DETAIL, stamp, tick);
    }
}
