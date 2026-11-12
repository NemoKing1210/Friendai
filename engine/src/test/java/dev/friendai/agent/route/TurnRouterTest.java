package dev.friendai.agent.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.plan.PlanSubject;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.turn.TurnKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class TurnRouterTest {
    private final TurnRouter router = new TurnRouter(IntentVocabulary.load());

    private RouteDecision player(String text, boolean goal) {
        return route(TurnKind.PLAYER, text, goal, false, true, false);
    }

    private RouteDecision route(
            TurnKind kind, String text, boolean goal, boolean blocked, boolean owner, boolean troubled) {
        return router.route(new RouteSignals(
                kind, text, goal, blocked, owner, troubled, false, false, false, false, 3L, "", 0L, List.of(), 0L));
    }

    @Test
    void blockedEventRoutesToRecovery() {
        RouteDecision d = route(TurnKind.EVENT, "", true, true, true, true);
        assertEquals(TurnMode.RECOVERY, d.mode());
        assertTrue(d.wantPlanner(), "a queued troubled plan may be replanned");
        assertEquals(new PlanSubject.TroubledPlan(3, ""), d.planning().subject());
        assertEquals(ToolGroup.values().length, d.groups().size());
    }

    @Test
    void anOwnerOrderInTheSameBatchAsAFailedPlanWinsThePlanner() {
        RouteDecision d = route(TurnKind.PLAYER, "build a house and bring iron", true, true, true, true);
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
    void gatheringOrdersFromAVoiceSessionKeepTheGatheringTools() {
        // In order: "Take a couple of bushes and a couple of clay we are standing on", "Yes, take it and follow me
        // afterwards", "Chop some firewood and follow me", "Then don't mine the red sand, only the bushes, they are
        // quick to collect with one click, aren't they?".
        for (String text : List.of(
                "Забери пару кустов и пару глины на которой мы стоим.",
                "Да, бери и следуй за мной после.",
                "Наруби дров и иди за мной",
                "Тогда красный песок не добывай, только кусты, их же быстро собрать одним кликом или нет?",
                "take the bushes and follow me",
                "grab some sand")) {
            RouteDecision d = player(text, false);
            assertFalse(d.mode() == TurnMode.CHAT, text);
            assertTrue(d.groups().contains(ToolGroup.GATHERING), text);
        }
    }

    @Test
    void takingIsATopicNotASecondKindOfWork() {
        // "Yes, take it and follow me afterwards": one movement order that refers to the previous reply.
        RouteDecision d = player("Да, бери и следуй за мной после.", false);
        assertEquals(TurnMode.ACTION, d.mode());
        assertFalse(d.wantPlanner(), "a follow-up that says 'take it' is not an objective to plan");
    }

    @Test
    void eatingAndSleepingShowTheSurvivalTools() {
        // The last two are "eat something" and "have a sleep" in Russian.
        for (String text : List.of("eat something", "sleep in the bed", "съешь что-нибудь", "поспи")) {
            assertTrue(player(text, false).groups().contains(ToolGroup.SURVIVAL), text);
        }
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
        RouteDecision d = route(TurnKind.EVENT, "", false, false, true, false);
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
        // "Make a plan for the house" and "plan it step by step" in Russian.
        assertEquals(TurnMode.GOAL, player("Составь план постройки дома", false).mode());
        assertEquals(
                TurnMode.GOAL, player("Распланируй, как построить дом", false).mode());
    }

    @Test
    void oneWordIsOneKindOfWorkEvenWhenTwoStemsMatchIt() {
        // "Get three cobblestones", "Get some stone", "Get iron ore": "добудь" matches both the gathering stem and the
        // obtain verb, and used to count as two kinds of work, sending a one-step order to the planner.
        for (String text : List.of("Добудь три булыжника.", "Добудь немного камня.", "Добудь железную руду.")) {
            RouteDecision d = player(text, false);
            assertEquals(TurnMode.ACTION, d.mode(), text);
            assertFalse(d.wantPlanner(), text);
            assertTrue(d.groups().contains(ToolGroup.GATHERING) && d.groups().contains(ToolGroup.OBTAIN), text);
        }
        // Two different words still make two kinds: "get stone and build a house".
        assertEquals(TurnMode.GOAL, player("Добудь камня и построй дом", false).mode());
    }

    @Test
    void bringingShowsTheHandOverTools() {
        // "Bring me eight planks" in Russian: the planks were made and never handed over, because the giving tools
        // were not visible in the turn.
        for (String text : List.of("Принеси мне восемь досок.", "bring me some bread", "fetch me a pickaxe")) {
            RouteDecision d = player(text, false);
            assertEquals(TurnMode.ACTION, d.mode(), text);
            assertTrue(d.groups().contains(ToolGroup.OBTAIN) && d.groups().contains(ToolGroup.INVENTORY), text);
        }
    }

    @Test
    void aPlanOrThenMarkerCountsOnlyAsAWholeWord() {
        for (String text :
                List.of("make some planks", "get me 10 oak planks", "plant some carrots", "strengthen the wall")) {
            RouteDecision d = player(text, false);
            assertEquals(TurnMode.ACTION, d.mode(), text);
            assertFalse(d.wantPlanner(), text);
        }
        assertEquals(TurnMode.GOAL, player("plan the farm for me", false).mode());
    }
}
