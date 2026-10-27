package dev.friendai.agent.goal;

import java.util.List;

/** What the prompt shows of the primary goal. */
public record GoalView(String text, List<String> unmet, String planStatus, String lastLesson) {
    public static final GoalView EMPTY = new GoalView("", List.of(), "", "");

    public GoalView {
        unmet = List.copyOf(unmet);
    }

    public boolean isEmpty() {
        return text == null || text.isBlank();
    }
}
