package dev.friendai.agent.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.brain.TurnKind;
import org.junit.jupiter.api.Test;

class TurnRouterTest {
    private final TurnRouter router = new TurnRouter(IntentVocabulary.load());

    private RouteDecision player(String text, boolean goal) {
        return router.route(new RouteSignals(TurnKind.PLAYER, text, goal, false, 0));
    }

    @Test
    void blockedEventRoutesToRecovery() {
        RouteDecision d = router.route(new RouteSignals(TurnKind.EVENT, "", true, true, 3));
        assertEquals(TurnMode.RECOVERY, d.mode());
        assertTrue(d.wantPlanner(), "an active goal may be replanned");
        assertEquals(ToolGroup.values().length, d.groups().size());
    }

    @Test
    void twoObjectivesWithoutGoalRouteToGoalWithPlanner() {
        RouteDecision en = player("build a house and bring iron", false);
        assertEquals(TurnMode.GOAL, en.mode());
        assertTrue(en.wantPlanner());
        // "Build a house and bring iron" in Russian.
        RouteDecision ru = player("Построй дом и принеси железо", false);
        assertEquals(TurnMode.GOAL, ru.mode());
        assertTrue(ru.wantPlanner());
    }

    @Test
    void oneKindOfWorkIsAnActionNotAGoal() {
        RouteDecision d = player("mine some iron", false);
        assertEquals(TurnMode.ACTION, d.mode());
        assertFalse(d.wantPlanner());
        assertTrue(d.groups().contains(ToolGroup.GATHERING));
        assertFalse(d.groups().contains(ToolGroup.COOKING));
    }

    @Test
    void buildAndPlaceAreOneKindOfWork() {
        assertEquals(TurnMode.ACTION, player("build a house and place a torch", false).mode());
    }

    @Test
    void questionRoutesToChat() {
        RouteDecision d = player("where is the nearest village?", false);
        assertEquals(TurnMode.CHAT, d.mode());
        assertFalse(d.groups().contains(ToolGroup.GATHERING));
        // "Where are you?" in Russian.
        assertEquals(TurnMode.CHAT, player("Где ты?", false).mode());
    }

    @Test
    void aQuestionThatContainsAnActionStaysAnAction() {
        assertEquals(TurnMode.ACTION, player("can you mine some iron?", false).mode());
    }

    @Test
    void unclearTextDefaultsToActionWithAllGroups() {
        RouteDecision d = player("hmm okay then lets see", false);
        assertEquals(TurnMode.ACTION, d.mode(), "a lone 'then' is not an objective");
        assertEquals(ToolGroup.values().length, d.groups().size());
        RouteDecision plain = player("zzz qqq", false);
        assertEquals(TurnMode.ACTION, plain.mode());
        assertEquals(ToolGroup.values().length, plain.groups().size());
    }

    @Test
    void emptyAndWhitespaceTextDoNotThrowAndDefaultToAction() {
        for (String text : new String[] {"", "   ", "\n\t", null}) {
            RouteDecision d = player(text, false);
            assertEquals(TurnMode.ACTION, d.mode(), "text: " + text);
            assertEquals(ToolGroup.values().length, d.groups().size());
        }
    }

    @Test
    void veryLongTextIsHandled() {
        RouteDecision d = player("mine ".repeat(5_000), false);
        assertEquals(TurnMode.ACTION, d.mode());
    }

    @Test
    void eventTurnRoutesToEvent() {
        RouteDecision d = router.route(new RouteSignals(TurnKind.EVENT, "", false, false, 0));
        assertEquals(TurnMode.EVENT, d.mode());
        assertFalse(d.wantPlanner());
    }

    @Test
    void activeGoalSuppressesWantPlanner() {
        RouteDecision d = player("build a house and bring iron", true);
        assertFalse(d.wantPlanner());
        assertEquals(TurnMode.ACTION, d.mode());
    }

    @Test
    void explicitPlanRequestWithOneVerbIsAGoal() {
        RouteDecision d = player("make a plan to mine iron", false);
        assertEquals(TurnMode.GOAL, d.mode());
        assertTrue(d.wantPlanner());
    }
}
