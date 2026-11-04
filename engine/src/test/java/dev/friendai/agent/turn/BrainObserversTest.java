package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrainObserversTest {
    private static final class Recall implements BrainObserver {
        int requests;
        int responses;
        int starts;
        int ends;
        int routes;
        int plannerAttempts;

        @Override
        public void onRouteDecision(dev.friendai.agent.route.RouteDecision decision) {
            routes++;
        }

        @Override
        public void onPlannerAttempt(PlannerAttempt attempt) {
            plannerAttempts++;
        }

        @Override
        public void onRequest(String system, List<Message> messages, List<ToolSpec> tools) {
            requests++;
        }

        @Override
        public void onResponse(LlmResponse response) {
            responses++;
        }

        @Override
        public void onTurnStart(long turn) {
            starts++;
        }

        @Override
        public void onTurnEnd(long turn, String outcome) {
            ends++;
        }
    }

    @Test
    void bothObserversReceiveRequestResponseAndTurnCallbacks() {
        Recall first = new Recall();
        Recall second = new Recall();
        BrainObserver both = BrainObservers.of(first, second);
        both.onTurnStart(1);
        both.onRequest("system", List.of(Message.user("hi")), List.of());
        both.onResponse(new LlmResponse("ok", List.of(), StopReason.END, new Usage(1, 1)));
        both.onTurnEnd(1, "delivered");
        assertEquals(1, first.requests);
        assertEquals(1, second.requests);
        assertEquals(1, first.responses);
        assertEquals(1, second.responses);
        assertEquals(1, first.starts);
        assertEquals(1, second.starts);
        assertEquals(1, first.ends);
        assertEquals(1, second.ends);
    }

    @Test
    void bothObserversReceiveRoutingAndPlannerCallbacks() {
        Recall first = new Recall();
        Recall second = new Recall();
        BrainObserver both = BrainObservers.of(first, second);
        both.onRouteDecision(new dev.friendai.agent.route.RouteDecision(
                dev.friendai.agent.route.TurnMode.ACTION,
                java.util.Set.of(),
                dev.friendai.agent.route.PlanningDecision.none("no-plan-occasion"),
                ""));
        both.onPlannerAttempt(new PlannerAttempt(
                new dev.friendai.agent.plan.PlanSubject.OwnerOrder("mine 3 logs"), 1, "request", "", -1L, 0L));
        assertEquals(1, first.routes);
        assertEquals(1, second.routes);
        assertEquals(1, first.plannerAttempts);
        assertEquals(1, second.plannerAttempts);
    }

    @Test
    void aThrowingObserverDoesNotHideTheEventFromTheSecond() {
        Recall second = new Recall();
        BrainObserver both = BrainObservers.of(
                new BrainObserver() {
                    @Override
                    public void onTurnStart(long turn) {
                        throw new IllegalStateException("boom");
                    }
                },
                second);
        both.onTurnStart(2);
        assertEquals(1, second.starts);
    }
}
