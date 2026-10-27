package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IdleWatchdogTest {
    private static final long NOW = 1_000_000L;
    private static final long QUIET_SINCE = NOW - 120_000;

    private static boolean due(long quietSince) {
        return IdleWatchdog.due(true, IdleWatchdog.Busy.NONE, quietSince, NOW, 120);
    }

    @Test
    void dueAtTheWindow() {
        assertTrue(due(QUIET_SINCE));
        assertTrue(due(QUIET_SINCE - 1));
    }

    @Test
    void notDueBeforeTheWindow() {
        assertFalse(due(QUIET_SINCE + 1));
        assertFalse(due(0));
        assertFalse(due(-5));
    }

    /** Off is one flag whether free time is off for the owner or on a server that disabled it. */
    @Test
    void freeTimeOffOrDisabledNeverFires() {
        assertFalse(IdleWatchdog.due(false, IdleWatchdog.Busy.NONE, QUIET_SINCE, NOW, 120));
    }

    @Test
    void anythingInProgressWaits() {
        assertFalse(busy(new IdleWatchdog.Busy(true, false, false, false, false, false, false)));
        assertFalse(busy(new IdleWatchdog.Busy(false, true, false, false, false, false, false)));
        assertFalse(busy(new IdleWatchdog.Busy(false, false, true, false, false, false, false)));
        assertFalse(busy(new IdleWatchdog.Busy(false, false, false, true, false, false, false)));
        assertFalse(busy(new IdleWatchdog.Busy(false, false, false, false, true, false, false)));
        assertFalse(busy(new IdleWatchdog.Busy(false, false, false, false, false, true, false)));
        assertFalse(busy(new IdleWatchdog.Busy(false, false, false, false, false, false, true)));
    }

    private static boolean busy(IdleWatchdog.Busy busy) {
        return IdleWatchdog.due(true, busy, QUIET_SINCE, NOW, 120);
    }
}
