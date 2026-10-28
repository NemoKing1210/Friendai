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
        assertTrue(GoalTickPolicy.shouldTick(new TickFacts(
                f.activeGoal(),
                f.queueIdleOrBlocked(),
                f.ownerOnline(),
                f.inCombatOrPaused(),
                f.turnInFlight(),
                f.nowMillis(),
                f.lastTickMillis(),
                Autonomy.AUTONOMOUS,
                30)));
    }

    @Test
    void noTickWithoutAGoal() {
        TickFacts f = ready();
        assertFalse(GoalTickPolicy.shouldTick(
                new TickFacts(false, true, true, false, false, NOW, NOW - 31_000, Autonomy.ASSISTIVE, 30)));
        assertTrue(GoalTickPolicy.shouldTick(f));
    }

    @Test
    void noTickWhilePassive() {
        assertFalse(GoalTickPolicy.shouldTick(
                new TickFacts(true, true, true, false, false, NOW, NOW - 31_000, Autonomy.PASSIVE, 30)));
    }

    @Test
    void noTickWhileBusyOrInCombatOrTalking() {
        assertFalse(
                GoalTickPolicy.shouldTick(
                        new TickFacts(true, false, true, false, false, NOW, NOW - 31_000, Autonomy.ASSISTIVE, 30)),
                "queue busy");
        assertFalse(
                GoalTickPolicy.shouldTick(
                        new TickFacts(true, true, true, true, false, NOW, NOW - 31_000, Autonomy.ASSISTIVE, 30)),
                "combat, paused, asleep or on an expedition");
        assertFalse(
                GoalTickPolicy.shouldTick(
                        new TickFacts(true, true, true, false, true, NOW, NOW - 31_000, Autonomy.ASSISTIVE, 30)),
                "turn in flight");
    }

    @Test
    void noTickWhenTheOwnerIsAway() {
        assertFalse(GoalTickPolicy.shouldTick(
                new TickFacts(true, true, false, false, false, NOW, NOW - 31_000, Autonomy.ASSISTIVE, 30)));
    }

    @Test
    void noTickBeforeTheInterval() {
        assertFalse(GoalTickPolicy.shouldTick(
                new TickFacts(true, true, true, false, false, NOW, NOW - 29_999, Autonomy.ASSISTIVE, 30)));
        assertTrue(GoalTickPolicy.shouldTick(
                new TickFacts(true, true, true, false, false, NOW, NOW - 30_000, Autonomy.ASSISTIVE, 30)));
    }

    private static TickFacts noGoal(Autonomy autonomy, long idleMillis) {
        return new TickFacts(false, true, true, false, false, NOW, NOW - idleMillis, autonomy, 30);
    }

    @Test
    void onlyAutonomousAsksWhatNextAndOnlyAfterALongWait() {
        assertTrue(GoalTickPolicy.shouldAskWhatNext(noGoal(Autonomy.AUTONOMOUS, 120_000)));
        assertFalse(GoalTickPolicy.shouldAskWhatNext(noGoal(Autonomy.AUTONOMOUS, 119_999)), "four intervals first");
        assertFalse(GoalTickPolicy.shouldAskWhatNext(noGoal(Autonomy.ASSISTIVE, 600_000)));
        assertFalse(GoalTickPolicy.shouldAskWhatNext(noGoal(Autonomy.PASSIVE, 600_000)));
    }

    @Test
    void passiveAutonomyNeverPostsGoalTicksOrQuestions() {
        TickFacts withGoal = new TickFacts(true, true, true, false, false, NOW, 0, Autonomy.PASSIVE, 30);
        assertFalse(GoalTickPolicy.shouldTick(withGoal));
        assertFalse(GoalTickPolicy.shouldAskWhatNext(noGoal(Autonomy.PASSIVE, 10_000_000)));
    }

    @Test
    void askingNeedsAFreeIdleOwnerPresentCompanion() {
        assertFalse(
                GoalTickPolicy.shouldAskWhatNext(
                        new TickFacts(true, true, true, false, false, NOW, 0, Autonomy.AUTONOMOUS, 30)),
                "a goal exists");
        assertFalse(
                GoalTickPolicy.shouldAskWhatNext(
                        new TickFacts(false, false, true, false, false, NOW, 0, Autonomy.AUTONOMOUS, 30)),
                "queue busy");
        assertFalse(
                GoalTickPolicy.shouldAskWhatNext(
                        new TickFacts(false, true, false, false, false, NOW, 0, Autonomy.AUTONOMOUS, 30)),
                "owner away");
        assertFalse(
                GoalTickPolicy.shouldAskWhatNext(
                        new TickFacts(false, true, true, true, false, NOW, 0, Autonomy.AUTONOMOUS, 30)),
                "in combat");
        assertFalse(
                GoalTickPolicy.shouldAskWhatNext(
                        new TickFacts(false, true, true, false, true, NOW, 0, Autonomy.AUTONOMOUS, 30)),
                "already talking");
    }

    @Test
    void idleNudgeIsAQuestionNotAnOrder() {
        String line = new BrainEvent.IdleNudge().toPromptLine();
        assertTrue(line.contains("ask your owner one short question"), line);
        assertTrue(line.contains("do not start any work without an answer"), line);
    }

    @Test
    void eventLineHasGoalAndUnmetCriteria() {
        assertEquals(
                "[event] goal \"iron armor\" is not finished; unmet: 12 ingots; full set worn; the queue is idle. "
                        + "Continue it or say what blocks you.",
                new BrainEvent.GoalTick("iron armor", List.of("12 ingots", "full set worn")).toPromptLine());
        assertEquals(
                "[event] goal \"iron armor\" is not finished; nothing is confirmed missing, check whether it is "
                        + "done; the queue is idle. Continue it or say what blocks you.",
                new BrainEvent.GoalTick("iron armor", List.of()).toPromptLine());
    }
}
