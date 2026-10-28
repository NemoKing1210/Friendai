package dev.friendai.knowledge.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EscortPolicyTest {
    private final EscortPolicy policy = new EscortPolicy(EscortPolicy.Config.DEFAULTS);

    private static EscortPolicy.Input input(
            boolean online, boolean autonomous, double distance, boolean wasNear, int wanted, boolean asked) {
        return new EscortPolicy.Input(online, autonomous, true, distance, wasNear, false, wanted, asked);
    }

    private static EscortPolicy.Input holding(double distance, int wanted) {
        return new EscortPolicy.Input(true, false, true, distance, true, true, wanted, false);
    }

    @Test
    void nearbyOwnerCapsTheRadiusToSight() {
        var decision = policy.decide(input(true, false, 10, false, 64, false));
        assertEquals(EscortPolicy.Action.CAP_RADIUS, decision.action());
        assertEquals(32, decision.radiusCap());
    }

    @Test
    void laggingOwnerIsWaitedForAndResumeClearsTheWait() {
        var wait = policy.decide(input(true, false, 60, true, 64, false));
        assertEquals(EscortPolicy.Action.WAIT, wait.action());
        assertEquals(0, wait.radiusCap());
        assertTrue(wait.message().contains("60"));

        var resumed = policy.decide(input(true, false, 20, true, 64, false));
        assertEquals(EscortPolicy.Action.CAP_RADIUS, resumed.action());
        assertEquals(32, resumed.radiusCap());
    }

    @Test
    void holdingOwnerResumesOnlyAtTheResumeDistance() {
        var stillWaiting = policy.decide(holding(30, 64));
        assertEquals(EscortPolicy.Action.WAIT, stillWaiting.action());
        var resumed = policy.decide(holding(24, 64));
        assertEquals(EscortPolicy.Action.CAP_RADIUS, resumed.action());
    }

    @Test
    void farOwnerIsAskedOnceWithConcreteOptions() {
        var ask = policy.decide(input(true, false, 60, false, 64, false));
        assertEquals(EscortPolicy.Action.ASK_ONCE, ask.action());
        assertEquals(32, ask.radiusCap());
        assertTrue(ask.message().contains("64"));

        var again = policy.decide(input(true, false, 60, false, 64, true));
        assertEquals(EscortPolicy.Action.NONE, again.action());
        assertTrue(again.message().isEmpty());
    }

    @Test
    void autonomousModeAsksNobodyAndLeavesOneLine() {
        var first = policy.decide(input(true, true, 60, false, 512, false));
        assertEquals(EscortPolicy.Action.AUTONOMOUS, first.action());
        assertEquals(512, first.radiusCap());
        assertTrue(first.message().contains("nobody to ask"));

        var second = policy.decide(input(true, true, 60, false, 512, true));
        assertEquals(EscortPolicy.Action.AUTONOMOUS, second.action());
        assertTrue(second.message().isEmpty());
    }

    @Test
    void offlineOrOtherDimensionOwnerLeavesTheSearchAlone() {
        assertEquals(
                EscortPolicy.Action.NONE,
                policy.decide(input(false, false, 0, false, 64, false)).action());
        var otherDimension = new EscortPolicy.Input(true, false, false, 0, false, false, 64, false);
        assertEquals(EscortPolicy.Action.NONE, policy.decide(otherDimension).action());
    }
}
