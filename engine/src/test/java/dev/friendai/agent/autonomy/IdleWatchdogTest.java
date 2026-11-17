package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.agent.autonomy.IdleWatchdog.Choice;
import org.junit.jupiter.api.Test;

class IdleWatchdogTest {
    private static final long NOW = 1_000_000L;

    @Test
    void restAndBlockedAgendasHaveNoChoiceToExpire() {
        assertEquals(Choice.NONE, IdleWatchdog.choice(false, false, 0, NOW, 120, 3_000));
    }

    @Test
    void aRequestGetsTheConfiguredWindowWhileThinking() {
        assertEquals(Choice.WAITING, IdleWatchdog.choice(true, true, NOW - 119_999, NOW, 120, 3_000));
        assertEquals(Choice.STALE_IN_FLIGHT, IdleWatchdog.choice(true, true, NOW - 120_000, NOW, 120, 3_000));
    }

    @Test
    void missingAndFinishedChoicesAreRecoverableAfterTheirGrace() {
        assertEquals(Choice.WAITING, IdleWatchdog.choice(true, false, NOW - 2_999, NOW, 120, 3_000));
        assertEquals(Choice.UNANSWERED, IdleWatchdog.choice(true, false, NOW - 3_000, NOW, 120, 3_000));
        assertEquals(Choice.UNANSWERED, IdleWatchdog.choice(true, false, 0, NOW, 120, 3_000));
    }
}
