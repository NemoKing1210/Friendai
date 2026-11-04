package dev.friendai.agent.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReplanBudgetTest {
    @Test
    void allowsTheConfiguredNumberOfAttempts() {
        ReplanBudget budget = new ReplanBudget();
        assertFalse(budget.exhausted(7, 2), "a fresh plan may be replanned");
        budget.attempt(7);
        assertFalse(budget.exhausted(7, 2));
        budget.attempt(7);
        assertTrue(budget.exhausted(7, 2), "the second attempt spends the budget");
    }

    @Test
    void settlingThePlanClearsItsBudget() {
        ReplanBudget budget = new ReplanBudget();
        budget.attempt(7);
        budget.attempt(7);
        budget.settled(7);
        assertFalse(budget.exhausted(7, 2));
    }

    @Test
    void eachPlanHasItsOwnBudget() {
        ReplanBudget budget = new ReplanBudget();
        budget.attempt(7);
        budget.attempt(7);
        assertTrue(budget.exhausted(7, 2));
        assertFalse(budget.exhausted(8, 2));
    }

    @Test
    void trackedPlansStayBounded() {
        ReplanBudget budget = new ReplanBudget();
        for (long plan = 0; plan < 200; plan++) {
            budget.attempt(plan);
        }
        assertTrue(budget.tracked() <= ReplanBudget.MAX_TRACKED_PLANS);
    }
}
