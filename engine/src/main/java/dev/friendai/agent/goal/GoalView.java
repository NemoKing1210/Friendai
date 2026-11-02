package dev.friendai.agent.goal;

import java.util.List;

/** What the prompt shows of the primary goal. */
public record GoalView(String text, List<String> unmet, String planStatus, String lastLesson, RiskClass risk) {
    public static final GoalView EMPTY = new GoalView("", List.of(), "", "", RiskClass.SAFE);

    public GoalView {
        unmet = List.copyOf(unmet);
        risk = risk == null ? RiskClass.SAFE : risk;
    }

    public boolean isEmpty() {
        return text == null || text.isBlank();
    }

    /** Whether the owner must be asked before this goal's work runs; derived from the risk class. */
    public boolean consulted() {
        return risk.needsOwner();
    }
}
