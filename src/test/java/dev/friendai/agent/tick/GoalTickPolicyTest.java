package dev.friendai.agent.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.brain.AgentSettings.Autonomy;
import dev.friendai.brain.BrainEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoalTickPolicyTest {
    private static final long NOW = 100_000;

    private static TickFacts ready() {
        return new TickFacts(true, true, true, false, false, NOW, NOW - 31_000, Autonomy.ASSISTIVE, 30);
    }

    @Test
    void tickWhenAllConditionsHold() {
        assertTrue(GoalTickPolicy.shouldTick(ready()));
    }

    @Test
    void autonomousAlsoTicks() {
        TickFacts f = ready();
        assertTrue(GoalTickPolicy.shouldTick(new TickFacts(f.activeGoal(), f.queueIdleOrBlocked(), f.ownerOnline(),
                f.inCombatOrPaused(), f.turnInFlight(), f.nowMillis(), f.lastTickMillis(), Autonomy.AUTONOMOUS, 30)));
    }

    @Test
    void noTickWithoutAGoal() {
        TickFacts f = ready();
        assertFalse(GoalTickPolicy.shouldTick(new TickFacts(false, true, true, false, false, NOW, NOW - 31_000,
                Autonomy.ASSISTIVE, 30)));
        assertTrue(GoalTickPolicy.shouldTick(f));
    }

    @Test
    void noTickWhilePassive() {
        assertFalse(GoalTickPolicy.shouldTick(new TickFacts(true, true, true, false, false, NOW, NOW - 31_000,
                Autonomy.PASSIVE, 30)));
    }

    @Test
    void noTickWhileBusyOrInCombatOrTalking() {
        assertFalse(GoalTickPolicy.shouldTick(new TickFacts(true, false, true, false, false, NOW, NOW - 31_000,
                Autonomy.ASSISTIVE, 30)), "queue busy");
        assertFalse(GoalTickPolicy.shouldTick(new TickFacts(true, true, true, true, false, NOW, NOW - 31_000,
                Autonomy.ASSISTIVE, 30)), "combat, paused, asleep or on an expedition");
        assertFalse(GoalTickPolicy.shouldTick(new TickFacts(true, true, true, false, true, NOW, NOW - 31_000,
                Autonomy.ASSISTIVE, 30)), "turn in flight");
    }

    @Test
    void noTickWhenTheOwnerIsAway() {
        assertFalse(GoalTickPolicy.shouldTick(new TickFacts(true, true, false, false, false, NOW, NOW - 31_000,
                Autonomy.ASSISTIVE, 30)));
    }

    @Test
    void noTickBeforeTheInterval() {
        assertFalse(GoalTickPolicy.shouldTick(new TickFacts(true, true, true, false, false, NOW, NOW - 29_999,
                Autonomy.ASSISTIVE, 30)));
        assertTrue(GoalTickPolicy.shouldTick(new TickFacts(true, true, true, false, false, NOW, NOW - 30_000,
                Autonomy.ASSISTIVE, 30)));
    }

    @Test
    void eventLineHasGoalAndUnmetCriteria() {
        assertEquals("[event] goal \"iron armor\" is not finished; unmet: 12 ingots; full set worn; the queue is idle. "
                        + "Continue it or say what blocks you.",
                new BrainEvent.GoalTick("iron armor", List.of("12 ingots", "full set worn")).toPromptLine());
        assertEquals("[event] goal \"iron armor\" is not finished; nothing is confirmed missing, check whether it is "
                        + "done; the queue is idle. Continue it or say what blocks you.",
                new BrainEvent.GoalTick("iron armor", List.of()).toPromptLine());
    }
}
