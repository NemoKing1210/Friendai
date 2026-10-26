package dev.friendai.agent.prompt;

import dev.friendai.agent.goal.GoalView;
import java.util.List;

/** Renders the {@code Mission:} prompt block, at most six lines. */
public final class MissionText {
    private static final int MAX_UNMET_SHOWN = 3;

    private MissionText() {}

    public static String render(GoalView view) {
        if (view == null || view.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("Mission:\n- Goal: ").append(view.text());
        List<String> unmet = view.unmet();
        if (!unmet.isEmpty()) {
            out.append("\n- Not yet confirmed: ")
                    .append(String.join("; ", unmet.subList(0, Math.min(MAX_UNMET_SHOWN, unmet.size()))));
        }
        if (!view.planStatus().isBlank()) {
            out.append("\n- Plan: ").append(view.planStatus());
        }
        if (!view.lastLesson().isBlank()) {
            out.append("\n- Lesson learned: ").append(view.lastLesson());
        }
        return out.toString();
    }
}
