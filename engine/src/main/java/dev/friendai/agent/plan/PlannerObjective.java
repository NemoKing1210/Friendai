package dev.friendai.agent.plan;

import dev.friendai.agent.autonomy.Shift;
import dev.friendai.agent.goal.Criterion;
import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.execution.queue.PlanRecord;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Turns a {@link PlanSubject} into the goal-shaped objective a planner prompt needs. Pure: the caller supplies the
 * plan, goal or shift it already loaded on the server thread.
 *
 * <p>A plan drafted for an order has no criteria on purpose: a plan must not be able to manufacture a verified goal.
 * Only a real goal, with its world-checkable criteria, keeps them.
 */
public final class PlannerObjective {
    /** Longest objective text carried into a planner prompt. */
    public static final int MAX_TEXT = 240;

    private static final String UNMET_SEPARATOR = "; still missing: ";

    private PlannerObjective() {}

    /** The planless objective for an owner or trusted order. */
    public static Goal forOrder(String text) {
        return new Goal(0L, bounded(text), List.of(), GoalStatus.ACTIVE, 0L, 0, List.of(), 0L, false);
    }

    /** An existing goal, with its criteria and lessons, plus the criteria the world still reports unmet. */
    public static Goal forGoal(Goal goal, List<String> unmet) {
        if (goal == null) return forOrder("");
        return new Goal(
                goal.id(),
                bounded(withUnmet(goal.text(), unmet)),
                goal.criteria(),
                goal.status(),
                goal.planId(),
                goal.replans(),
                goal.lessons(),
                goal.createdTick(),
                goal.background(),
                goal.deadlineTick());
    }

    /** A queued plan: the goal that owns it when there is one, otherwise the plan's own bounded text. */
    public static Goal forPlan(PlanRecord plan, Optional<Goal> linkedGoal) {
        if (linkedGoal != null && linkedGoal.isPresent()) {
            Goal goal = linkedGoal.get();
            return new Goal(
                    goal.id(),
                    bounded(goal.text()),
                    goal.criteria(),
                    goal.status(),
                    plan.id(),
                    goal.replans(),
                    goal.lessons(),
                    goal.createdTick(),
                    goal.background(),
                    goal.deadlineTick());
        }
        return new Goal(
                plan.id(), bounded(plan.goal()), List.of(), GoalStatus.ACTIVE, plan.id(), 0, List.of(), 0L, false);
    }

    /** One free-time shift, with its own criteria and the lessons earlier shifts left behind. */
    public static Goal forShift(Shift shift, List<String> lessons) {
        List<Criterion> criteria = shift.criteria().stream()
                .map(text -> new Criterion(text, Optional.empty()))
                .toList();
        return new Goal(
                shift.id(),
                bounded(shift.goal()),
                criteria,
                GoalStatus.ACTIVE,
                0L,
                0,
                lessons == null ? List.of() : List.copyOf(lessons),
                0L,
                false);
    }

    private static String withUnmet(String text, List<String> unmet) {
        String base = text == null ? "" : text.strip();
        if (unmet == null || unmet.isEmpty()) return base;
        String lines = unmet.stream()
                .filter(line -> line != null && !line.isBlank())
                .map(String::strip)
                .collect(Collectors.joining(", "));
        return lines.isEmpty() ? base : base + UNMET_SEPARATOR + lines;
    }

    private static String bounded(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) : value;
    }
}
