package dev.friendai.agent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.autonomy.NeedKind;
import dev.friendai.agent.autonomy.Shift;
import dev.friendai.agent.goal.Criterion;
import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.PlanRecord;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlannerObjectiveTest {
    @Test
    void anOrderIsAPlanlessGoal() {
        Goal order = PlannerObjective.forOrder("build a house");
        assertEquals("build a house", order.text());
        assertTrue(order.criteria().isEmpty());
        assertEquals(GoalStatus.ACTIVE, order.status());
        assertEquals(0, order.planId());
        assertEquals(0, order.id());
    }

    @Test
    void anOrderIsBoundedAndNullSafe() {
        assertEquals(
                PlannerObjective.MAX_TEXT,
                PlannerObjective.forOrder("x".repeat(500)).text().length());
        assertEquals("", PlannerObjective.forOrder(null).text());
    }

    @Test
    void aGoalCarriesItsCriteriaAndUnmetLines() {
        Goal linked = new Goal(4, "stock the chest", List.of(), GoalStatus.ACTIVE, 9, 0, List.of(), 0, false);
        Goal withUnmet = PlannerObjective.forGoal(linked, List.of("2 logs missing"));
        assertTrue(withUnmet.text().contains("2 logs missing"));
        assertTrue(withUnmet.text().startsWith("stock the chest"));
        assertEquals(9, withUnmet.planId());
        assertEquals(4, withUnmet.id());
        assertEquals(GoalStatus.ACTIVE, withUnmet.status());
    }

    @Test
    void aGoalObjectiveIsBounded() {
        Goal linked = new Goal(4, "x".repeat(400), List.of(), GoalStatus.ACTIVE, 9, 0, List.of(), 0, false);
        assertEquals(
                PlannerObjective.MAX_TEXT,
                PlannerObjective.forGoal(linked, List.of("still missing"))
                        .text()
                        .length());
    }

    @Test
    void aPlanUsesItsLinkedGoal() {
        Goal linked = new Goal(
                4,
                "stock the chest",
                List.of(new Criterion("20 logs", Optional.empty())),
                GoalStatus.ACTIVE,
                9,
                0,
                List.of("bring a shovel"),
                0,
                false);
        PlanRecord plan = new PlanRecord(9, "stock the chest", 0, 0, 0, FailurePolicy.ASK, null, null);
        Goal objective = PlannerObjective.forPlan(plan, Optional.of(linked));
        assertEquals(9, objective.planId());
        assertEquals(4, objective.id());
        assertEquals(1, objective.criteria().size());
        assertEquals(List.of("bring a shovel"), objective.lessons());
    }

    @Test
    void aPlanWithoutAGoalUsesThePlanTextAndNoCriteria() {
        PlanRecord plan = new PlanRecord(9, "stock the chest", 0, 0, 0, FailurePolicy.ASK, null, null);
        Goal objective = PlannerObjective.forPlan(plan, Optional.empty());
        assertEquals("stock the chest", objective.text());
        assertTrue(objective.criteria().isEmpty());
        assertEquals(9, objective.planId());
    }

    @Test
    void aShiftMapsItsCriteriaAndLessons() {
        Shift shift =
                new Shift(3, "gather wood", List.of("20 logs"), "the chest is empty", 0, 0, 0, false, NeedKind.COLLECT);
        Goal objective = PlannerObjective.forShift(shift, List.of("last time the axe broke"));
        assertEquals(3, objective.id());
        assertEquals("gather wood", objective.text());
        assertEquals(1, objective.criteria().size());
        assertEquals("20 logs", objective.criteria().getFirst().text());
        assertEquals(List.of("last time the axe broke"), objective.lessons());
    }
}
