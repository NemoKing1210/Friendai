package dev.friendai.agent.goal;

import dev.friendai.queue.GuardFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The companion's persistent intentions: at most one primary goal and a few background ones. Pure; the owner's
 * memory book stores the encoded form ({@link GoalSave}). Finished goals leave the board.
 */
public final class GoalBoard {
    public static final int MAX_TEXT = 200;
    public static final int MAX_CRITERIA = 6;
    public static final int MAX_CRITERION_TEXT = 160;
    public static final int MAX_LESSONS = 5;
    public static final int MAX_LESSON_TEXT = 160;

    private final int maxBackground;
    private final int maxReplans;
    private final List<Goal> goals = new ArrayList<>();
    private long nextId = 1;

    public GoalBoard(int maxBackground, int maxReplans) {
        this.maxBackground = maxBackground;
        this.maxReplans = maxReplans;
    }

    /** Rebuilds a board from saved parts; used by {@link GoalSave}. */
    static GoalBoard restore(int maxBackground, int maxReplans, long nextId, List<Goal> saved) {
        GoalBoard board = new GoalBoard(maxBackground, maxReplans);
        for (Goal goal : saved) {
            if (!goal.status().terminal()) {
                board.goals.add(goal);
                board.nextId = Math.max(board.nextId, goal.id() + 1);
            }
        }
        board.nextId = Math.max(board.nextId, nextId);
        return board;
    }

    public GoalResult<Goal> set(String text, List<Criterion> criteria, boolean background, long tick) {
        String cleaned = clean(text, MAX_TEXT);
        if (cleaned.isEmpty()) {
            return GoalResult.fail("The goal needs a short text.");
        }
        GoalResult<List<Criterion>> checked = checkCriteria(criteria);
        if (!checked.ok()) {
            return GoalResult.fail(checked.error());
        }
        if (background) {
            if (background().size() >= maxBackground) {
                return GoalResult.fail(
                        "There are already " + maxBackground + " background goals; finish one before adding another.");
            }
        } else if (primary().isPresent()) {
            return GoalResult.fail("There is already a primary goal (#"
                    + primary().get().id() + "); finish or update it first, or add this one as a background goal.");
        }
        Goal goal = new Goal(nextId++, cleaned, checked.value(), GoalStatus.ACTIVE, 0, 0, List.of(), tick, background);
        goals.add(goal);
        return GoalResult.ok(goal);
    }

    public Optional<Goal> primary() {
        return goals.stream().filter(g -> !g.background()).findFirst();
    }

    public List<Goal> background() {
        return goals.stream().filter(Goal::background).toList();
    }

    public List<Goal> all() {
        return List.copyOf(goals);
    }

    long nextId() {
        return nextId;
    }

    public Optional<Goal> find(long id) {
        return goals.stream().filter(g -> g.id() == id).findFirst();
    }

    /** Edits a goal; status may only move between ACTIVE and BLOCKED here (use {@link #finish} to end it). */
    public GoalResult<Goal> update(
            long id, Optional<String> text, Optional<List<Criterion>> criteria, Optional<GoalStatus> status) {
        Optional<Goal> found = find(id);
        if (found.isEmpty()) {
            return GoalResult.fail("No goal #" + id + ".");
        }
        Goal goal = found.get();
        if (text.isPresent()) {
            String cleaned = clean(text.get(), MAX_TEXT);
            if (cleaned.isEmpty()) {
                return GoalResult.fail("The goal needs a short text.");
            }
            goal = goal.withText(cleaned);
        }
        if (criteria.isPresent()) {
            GoalResult<List<Criterion>> checked = checkCriteria(criteria.get());
            if (!checked.ok()) {
                return GoalResult.fail(checked.error());
            }
            goal = goal.withCriteria(checked.value());
        }
        if (status.isPresent()) {
            if (status.get().terminal()) {
                return GoalResult.fail("Use finish_goal to end a goal.");
            }
            if (status.get() == GoalStatus.ACTIVE && goal.status() == GoalStatus.BLOCKED) {
                // The owner (or the model, on their word) lifted the block: start over with a fresh budget.
                goal = goal.withReplans(0);
            }
            goal = goal.withStatus(status.get());
        }
        replace(goal);
        return GoalResult.ok(goal);
    }

    /** Ends a goal as done or abandoned and removes it from the board. */
    public GoalResult<Goal> finish(long id, boolean done, String reason) {
        Optional<Goal> found = find(id);
        if (found.isEmpty()) {
            return GoalResult.fail("No goal #" + id + ".");
        }
        Goal ended = found.get().withStatus(done ? GoalStatus.DONE : GoalStatus.ABANDONED);
        goals.removeIf(g -> g.id() == id);
        return GoalResult.ok(ended);
    }

    public void linkPlan(long goalId, long planId) {
        find(goalId).ifPresent(g -> replace(g.withPlan(planId)));
    }

    /** Spends one replan; returns false and blocks the goal when the budget is already used up. */
    public boolean recordReplan(long goalId) {
        Optional<Goal> found = find(goalId);
        if (found.isEmpty()) {
            return false;
        }
        Goal goal = found.get();
        if (goal.replans() >= maxReplans) {
            replace(goal.withStatus(GoalStatus.BLOCKED));
            return false;
        }
        replace(goal.withReplans(goal.replans() + 1));
        return true;
    }

    public void addLesson(long goalId, String lesson) {
        Optional<Goal> found = find(goalId);
        String cleaned = clean(lesson, MAX_LESSON_TEXT);
        if (found.isEmpty() || cleaned.isEmpty() || found.get().lessons().contains(cleaned)) {
            return;
        }
        List<String> lessons = new ArrayList<>(found.get().lessons());
        lessons.add(cleaned);
        while (lessons.size() > MAX_LESSONS) {
            lessons.remove(0);
        }
        replace(found.get().withLessons(lessons));
    }

    /** Criteria the world does not yet confirm: a failing guard, or a criterion with no guard at all. */
    public List<Criterion> unmet(long goalId, GuardFacts facts) {
        return find(goalId)
                .map(g -> g.criteria().stream()
                        .filter(c -> c.check().isEmpty() || !c.check().get().holds(facts))
                        .toList())
                .orElse(List.of());
    }

    /** The prompt view of the primary goal; empty when there is none. */
    public GoalView view(String planStatus, GuardFacts facts) {
        Optional<Goal> primary = primary();
        if (primary.isEmpty()) {
            return GoalView.EMPTY;
        }
        Goal goal = primary.get();
        List<String> lessons = goal.lessons();
        return new GoalView(
                goal.text(),
                unmet(goal.id(), facts).stream().map(Criterion::text).toList(),
                planStatus == null ? "" : planStatus,
                lessons.isEmpty() ? "" : lessons.get(lessons.size() - 1));
    }

    private void replace(Goal updated) {
        for (int i = 0; i < goals.size(); i++) {
            if (goals.get(i).id() == updated.id()) {
                goals.set(i, updated);
                return;
            }
        }
    }

    private static GoalResult<List<Criterion>> checkCriteria(List<Criterion> criteria) {
        if (criteria.size() > MAX_CRITERIA) {
            return GoalResult.fail("At most " + MAX_CRITERIA + " criteria per goal.");
        }
        List<Criterion> cleaned = new ArrayList<>();
        for (Criterion c : criteria) {
            String text = clean(c.text(), MAX_CRITERION_TEXT);
            if (text.isEmpty()) {
                return GoalResult.fail("A criterion needs text.");
            }
            cleaned.add(new Criterion(text, c.check()));
        }
        return GoalResult.ok(cleaned);
    }

    private static String clean(String text, int max) {
        if (text == null) {
            return "";
        }
        String single = text.strip().replaceAll("\\s+", " ");
        return single.length() > max ? single.substring(0, max) : single;
    }
}
