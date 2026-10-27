package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.brain.AgentSettings.Autonomy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IdleStartPolicyTest {
    private static final AutonomySettings S = AutonomySettings.DEFAULTS;
    private static final long NOW = 10_000_000;
    private static final long FIVE_MIN = 5 * 60_000;

    private static boolean start(FreeTime state, AutonomySettings s, Autonomy ceiling, boolean goal, boolean idle,
                                 boolean combat, boolean online, long lastOwner, long lastEnd) {
        return IdleStartPolicy.shouldStart(state, s, ceiling, goal, idle, combat, online, NOW, lastOwner, lastEnd);
    }

    @Test
    void startsAfterIdleMinutes() {
        assertTrue(start(FreeTime.OFF, S, Autonomy.ASSISTIVE, false, true, false, true, NOW - FIVE_MIN, 0));
    }

    @Test
    void notBeforeIdleMinutes() {
        assertFalse(start(FreeTime.OFF, S, Autonomy.ASSISTIVE, false, true, false, true, NOW - FIVE_MIN + 1, 0));
    }

    @Test
    void notWhenAlreadyOn() {
        var on = new FreeTime(true, FreeTime.Origin.COMMAND, "", 0, null, false, 1, List.of(), List.of(), Map.of(),
                List.of());
        assertFalse(start(on, S, Autonomy.ASSISTIVE, false, true, false, true, 0, 0));
    }

    @Test
    void notWhenIdleStartDisabled() {
        var noIdle = new AutonomySettings(true, false, 5, 60, true, 20, 6, 2, 10, true, true, List.of());
        assertFalse(start(FreeTime.OFF, noIdle, Autonomy.ASSISTIVE, false, true, false, true, 0, 0));
        var disabled = new AutonomySettings(false, true, 5, 60, true, 20, 6, 2, 10, true, true, List.of());
        assertFalse(start(FreeTime.OFF, disabled, Autonomy.ASSISTIVE, false, true, false, true, 0, 0));
    }

    @Test
    void notPassive() {
        assertFalse(start(FreeTime.OFF, S, Autonomy.PASSIVE, false, true, false, true, 0, 0));
    }

    @Test
    void notWithGoalOrWorkOrCombat() {
        assertFalse(start(FreeTime.OFF, S, Autonomy.ASSISTIVE, true, true, false, true, 0, 0));
        assertFalse(start(FreeTime.OFF, S, Autonomy.ASSISTIVE, false, false, false, true, 0, 0));
        assertFalse(start(FreeTime.OFF, S, Autonomy.ASSISTIVE, false, true, true, true, 0, 0));
    }

    @Test
    void offlineNeedsPermission() {
        assertTrue(start(FreeTime.OFF, S, Autonomy.ASSISTIVE, false, true, false, false, 0, 0));
        var noOffline = new AutonomySettings(true, true, 5, 60, false, 20, 6, 2, 10, true, true, List.of());
        assertFalse(start(FreeTime.OFF, noOffline, Autonomy.ASSISTIVE, false, true, false, false, 0, 0));
    }

    @Test
    void restartNeedsFreshPeriodAfterEnd() {
        assertFalse(start(FreeTime.OFF, S, Autonomy.ASSISTIVE, false, true, false, true, 0, NOW - 60_000));
        assertTrue(start(FreeTime.OFF, S, Autonomy.ASSISTIVE, false, true, false, true, 0, NOW - FIVE_MIN));
    }
}
