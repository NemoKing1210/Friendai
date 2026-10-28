package dev.friendai.agent.prompt;

import dev.friendai.agent.goal.GoalView;
import java.util.List;

/** Renders the {@code Mission:} prompt block, at most six lines. */
public final class MissionText {
    private static final int MAX_UNMET_SHOWN = 3;

    private MissionText() {}

    public static String render(GoalView view) {
        return render(view, "");
    }

    /** @param plannerNote what the planner just did for this turn, or empty */
    public static String render(GoalView view, String plannerNote) {
        boolean hasGoal = view != null && !view.isEmpty();
        boolean hasNote = plannerNote != null && !plannerNote.isBlank();
        if (!hasGoal && !hasNote) {
            return "";
        }
        StringBuilder out = new StringBuilder("Mission:");
        if (hasGoal) {
            out.append("\n- Goal: ").append(view.text());
            List<String> unmet = view.unmet();
            if (!unmet.isEmpty()) {
                out.append("\n- Not yet confirmed: ")
                        .append(String.join("; ", unmet.subList(0, Math.min(MAX_UNMET_SHOWN, unmet.size()))));
            }
            if (!view.planStatus().isBlank()) {
                out.append("\n- Plan: ")
                        .append(view.planStatus())
                        .append(" (when/then/else; server selects, repeat cap 64)");
            }
            if (!view.lastLesson().isBlank()) {
                out.append("\n- Lesson learned: ").append(view.lastLesson());
            }
        }
        if (hasNote) {
            out.append("\n- Planner: ").append(plannerNote.strip());
        }
        return out.toString();
    }
}
