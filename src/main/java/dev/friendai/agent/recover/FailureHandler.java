package dev.friendai.agent.recover;

import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalBoard;
import dev.friendai.queue.GuardFacts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the extra text of a task or plan event ({@code facts:}, {@code unmet:}, {@code diagnosis:} and the
 * replan-budget notice) and keeps the owner's goal board in step: lessons from failures and the replan count.
 */
public final class FailureHandler {
    static final String TRIED = "tried: ";
    public static final String BUDGET_SPENT =
            "replan budget spent: tell the player what is blocking you and ask for help";

    private final RecoveryAdvisor advisor;

    public FailureHandler(RecoveryAdvisor advisor) {
        this.advisor = advisor;
    }

    /**
     * A task or plan failed.
     *
     * @param planId the queue plan that failed, or zero for a direct task
     * @param factsLine the {@code facts:} text from the outcome checker, possibly empty
     * @return text to append to the event line, possibly empty
     */
    public String onFailure(GoalBoard board, long planId, String tool, String failureText, String factsLine) {
        if (isCancellation(failureText)) {
            // The player or the brain ended the work on purpose; that is no reason to diagnose, learn or replan.
            return factsLine;
        }
        Optional<Goal> goal = goalOfPlan(board, planId);
        Set<String> tried = new HashSet<>();
        goal.ifPresent(g -> g.lessons().stream()
                .filter(l -> l.startsWith(TRIED))
                .forEach(l -> tried.add(l.substring(TRIED.length()))));
        Diagnosis diagnosis = advisor.diagnose(tool, failureText, tried);
        List<String> parts = new ArrayList<>();
        if (!factsLine.isBlank()) {
            parts.add(factsLine);
        }
        if (diagnosis.kind() != FailureKind.UNKNOWN) {
            parts.add(diagnosis.line());
        }
        if (goal.isPresent()) {
            long id = goal.get().id();
            if (diagnosis.kind() != FailureKind.UNKNOWN) {
                board.addLesson(id, diagnosis.detail());
                if (!diagnosis.remedies().isEmpty()) {
                    board.addLesson(id, TRIED + diagnosis.remedies().get(0));
                }
            }
            if (!board.recordReplan(id)) {
                parts.add(BUDGET_SPENT);
            }
        }
        return String.join("; ", parts);
    }

    /** True for the summaries of work that was stopped or cancelled on purpose rather than failed. */
    public static boolean isCancellation(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.strip().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("stopped") || lower.startsWith("cancelled") || lower.startsWith("canceled");
    }

    /**
     * A task or plan finished well.
     *
     * @return text to append to the event line, possibly empty
     */
    public String onSuccess(GoalBoard board, long planId, String factsLine, GuardFacts facts) {
        List<String> parts = new ArrayList<>();
        if (!factsLine.isBlank()) {
            parts.add(factsLine);
        }
        Optional<Goal> goal = goalOfPlan(board, planId);
        if (goal.isPresent()) {
            var unmet = board.unmet(goal.get().id(), facts);
            if (!unmet.isEmpty()) {
                parts.add("unmet: " + String.join("; ", unmet.stream().map(c -> c.text()).toList()));
            } else if (!goal.get().criteria().isEmpty()) {
                parts.add("all criteria confirmed: call finish_goal");
            }
        }
        return String.join("; ", parts);
    }

    private static Optional<Goal> goalOfPlan(GoalBoard board, long planId) {
        if (planId == 0) {
            return Optional.empty();
        }
        return board.all().stream().filter(g -> g.planId() == planId).findFirst();
    }
}
