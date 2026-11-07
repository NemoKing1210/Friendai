package dev.friendai.agent.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.plan.PlanSubject;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.turn.TurnKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class TurnRouterTest {
    private final TurnRouter router = new TurnRouter(IntentVocabulary.load());

    private RouteDecision player(String text, boolean goal) {
        return route(TurnKind.PLAYER, text, goal, false, 0, true, false);
    }

    private RouteDecision route(
            TurnKind kind, String text, boolean goal, boolean blocked, int steps, boolean owner, boolean troubled) {
        return route(kind, text, goal, blocked, steps, owner, troubled, 3L);
    }

    private RouteDecision route(
            TurnKind kind,
            String text,
            boolean goal,
            boolean blocked,
            int steps,
            boolean owner,
            boolean troubled,
            long troubledPlanId) {
        return router.route(new RouteSignals(
                kind,
                text,
                goal,
                blocked,
                steps,
                owner,
                troubled,
                false,
                false,
                false,
                false,
                troubledPlanId,
                "",
                0L,
                List.of(),
                0L));
    }

    @Test
    void blockedEventRoutesToRecovery() {
        RouteDecision d = route(TurnKind.EVENT, "", true, true, 3, true, true);
        assertEquals(TurnMode.RECOVERY, d.mode());
        assertTrue(d.wantPlanner(), "a queued troubled plan may be replanned");
        assertEquals(new PlanSubject.TroubledPlan(3, ""), d.planning().subject());
        assertEquals(ToolGroup.values().length, d.groups().size());
    }

    @Test
    void aCancelledTroubledPlanIsNotNamedAsThePlannerSubject() {
        // The batch still holds the failure, but no plan id names a plan that exists.
        RouteDecision d = route(TurnKind.EVENT, "", true, true, 0, true, true, 0L);
        assertEquals(TurnMode.RECOVERY, d.mode());
        assertFalse(d.wantPlanner(), "nothing is left to repair, so the planner is not consulted");
        assertNull(d.planning().subject());
    }

    @Test
    void anOwnerOrderInTheSameBatchAsAFailedPlanWinsThePlanner() {
        RouteDecision d = route(TurnKind.PLAYER, "build a house and bring iron", true, true, 3, true, true);
        assertEquals(TurnMode.RECOVERY, d.mode());
        assertTrue(d.planning().subject() instanceof PlanSubject.OwnerOrder, "the owner's line is the plan's subject");
        assertEquals("owner-line-first", d.planning().reason());
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
    void freeTimePhrasesExposeAutonomy() {
        assertTrue(player("do your own thing", false).groups().contains(ToolGroup.AUTONOMY));
        assertTrue(player("do whatever you want", false).groups().contains(ToolGroup.AUTONOMY));
        // "Mind your own business" / "do what you want" / "free time" in Russian.
        assertTrue(player("займись своими делами", false).groups().contains(ToolGroup.AUTONOMY));
        assertTrue(player("делай что хочешь", false).groups().contains(ToolGroup.AUTONOMY));
        assertTrue(player("у тебя свободное время", false).groups().contains(ToolGroup.AUTONOMY));
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
        assertEquals(
                TurnMode.ACTION,
                player("build a house and place a torch", false).mode());
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
    void tradingQuestionsStayChatAndMayUseReadOnlyTradeContext() {
        RouteDecision en = player("What trades does the villager have?", false);
        assertEquals(TurnMode.CHAT, en.mode());
        assertTrue(en.groups().contains(ToolGroup.TRADING));
        assertFalse(en.groups().contains(ToolGroup.COMBAT));

        RouteDecision ru = player("Какие сделки есть у жителя?", false);
        assertEquals(TurnMode.CHAT, ru.mode());
        assertTrue(ru.groups().contains(ToolGroup.TRADING));
    }

    @Test
    void shoppingAdviceQuestionsDoNotUnlockSpendingTools() {
        for (String text : List.of("Should I buy a diamond sword?", "Стоит ли покупать карту?")) {
            RouteDecision d = player(text, false);
            assertEquals(TurnMode.CHAT, d.mode(), text);
            assertFalse(d.groups().contains(ToolGroup.TRADING), text);
        }
    }

    @Test
    void explicitBuyingAndSellingRequestsRouteToTrading() {
        for (String text : List.of(
                "Buy a map from the villager",
                "Sell my wheat to the farmer",
                "Купи карту у жителя",
                "Продай пшеницу фермеру")) {
            RouteDecision d = player(text, false);
            assertEquals(TurnMode.ACTION, d.mode(), text);
            assertTrue(d.groups().contains(ToolGroup.TRADING), text);
        }
    }

    @Test
    void onlyTradingActionVerbsCreateSpendingIntent() {
        TurnRouter router = new TurnRouter(IntentVocabulary.load());
        for (String text :
                List.of("Buy a map", "Продай пшеницу", "barter with the piglin", "пополнить запасы жителя")) {
            assertTrue(router.explicitlyRequestsTrading(text), text);
        }
        for (String text : List.of("get emeralds", "What offers does the villager have?", "hmm okay then")) {
            assertFalse(router.explicitlyRequestsTrading(text), text);
        }
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
        RouteDecision d = route(TurnKind.EVENT, "", false, false, 0, true, false);
        assertEquals(TurnMode.EVENT, d.mode());
        assertFalse(d.wantPlanner());
    }

    @Test
    void anOwnerOrderOverAnActiveGoalStillPlans() {
        RouteDecision d = player("build a house and bring iron", true);
        assertEquals(TurnMode.ACTION, d.mode());
        assertEquals(PlanNeed.REQUIRED, d.planning().need());
        assertTrue(d.wantPlanner());
        assertEquals(
                new PlanSubject.OwnerOrder("build a house and bring iron"),
                d.planning().subject());
    }

    @Test
    void planningDecisionMatchesTheRouter() {
        assertEquals(
                PlanNeed.REQUIRED,
                player("build a house and bring iron", false).planning().need());
        assertEquals(PlanNeed.NONE, player("mine some iron", false).planning().need());
        assertEquals(
                PlanNeed.NONE, player("where is the village?", false).planning().need());
        assertTrue(player("build a house and bring iron", false).wantPlanner());
    }

    @Test
    void explicitPlanRequestWithOneVerbIsAGoal() {
        RouteDecision d = player("make a plan to mine iron", false);
        assertEquals(TurnMode.GOAL, d.mode());
        assertTrue(d.wantPlanner());
    }
}
