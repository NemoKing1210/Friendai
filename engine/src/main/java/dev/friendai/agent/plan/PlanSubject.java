package dev.friendai.agent.plan;

import java.util.List;

/**
 * What a plan is for. A subject is the one thing a planner needs besides the live world facts, and every
 * planning occasion is one variant: a new occasion is a new record here, not a branch in the turn loop.
 *
 * @see dev.friendai.agent.route.PlanningPolicy
 */
public sealed interface PlanSubject {
    /** An owner or trusted order that is planned before the model answers. */
    record OwnerOrder(String text) implements PlanSubject {
        public OwnerOrder {
            text = text == null ? "" : text;
        }
    }

    /** The model called {@code set_goal} on this turn and queued no plan itself. */
    record ModelGoal(long goalId) implements PlanSubject {}

    /** An active goal whose criteria are still unmet and which has no queued work. */
    record ActiveGoal(long goalId, List<String> unmet) implements PlanSubject {
        public ActiveGoal {
            unmet = unmet == null ? List.of() : List.copyOf(unmet);
        }
    }

    /** A queued plan that failed or is blocked and still has replans left. */
    record TroubledPlan(long planId, String failure) implements PlanSubject {
        public TroubledPlan {
            failure = failure == null ? "" : failure;
        }
    }

    /** One free-time shift that has no plan yet. */
    record FreeTimeShift(long shiftId) implements PlanSubject {}
}
