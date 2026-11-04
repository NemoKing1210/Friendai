package dev.friendai.agent.goal;

import java.util.List;

/**
 * What the prompt shows of the primary goal.
 *
 * @param deadline the deadline phrase ({@code due in 12 minutes}, {@code overdue by 3 minutes}), empty when none
 * @param goalId the primary goal's id, or zero when there is none
 * @param planQueued whether the goal's plan still has queued work; the planning policy asks this and never parses
 *     {@code planStatus}
 */
public record GoalView(
        String text,
        List<String> unmet,
        String planStatus,
        String lastLesson,
        RiskClass risk,
        String deadline,
        long goalId,
        boolean planQueued) {
    public static final GoalView EMPTY = new GoalView("", List.of(), "", "", RiskClass.SAFE, "", 0, false);

    public GoalView {
        unmet = List.copyOf(unmet);
        risk = risk == null ? RiskClass.SAFE : risk;
        deadline = deadline == null ? "" : deadline;
    }

    /** A view of a goal without a deadline. */
    public GoalView(String text, List<String> unmet, String planStatus, String lastLesson, RiskClass risk) {
        this(text, unmet, planStatus, lastLesson, risk, "", 0, false);
    }

    /** A view without a goal id or queued-work fact. */
    public GoalView(
            String text, List<String> unmet, String planStatus, String lastLesson, RiskClass risk, String deadline) {
        this(text, unmet, planStatus, lastLesson, risk, deadline, 0, false);
    }

    public boolean isEmpty() {
        return text == null || text.isBlank();
    }

    /** Whether the owner must be asked before this goal's work runs; derived from the risk class. */
    public boolean consulted() {
        return risk.needsOwner();
    }
}
