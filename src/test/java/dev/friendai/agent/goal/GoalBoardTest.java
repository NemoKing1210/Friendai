package dev.friendai.agent.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.queue.Guard;
import dev.friendai.queue.GuardFacts;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GoalBoardTest {
    private static Criterion text(String t) {
        return new Criterion(t, Optional.empty());
    }

    private static GuardFacts items(Map<String, Integer> items) {
        return new GuardFacts() {
            @Override public int countItems(String id) { return items.getOrDefault(id, 0); }
            @Override public int emptySlots() { return 10; }
            @Override public float healthFraction() { return 1f; }
            @Override public int ownerDistance() { return 0; }
            @Override public int countBlocks(String id, int radius) { return 0; }
            @Override public String dimension() { return "minecraft:overworld"; }
        };
    }

    @Test
    void setsPrimaryThenBackgroundUpToThree() {
        GoalBoard board = new GoalBoard(3, 3);
        assertTrue(board.set("build a house", List.of(text("roof done")), false, 1).ok());
        for (int i = 0; i < 3; i++) {
            assertTrue(board.set("bg" + i, List.of(), true, 1).ok());
        }
        assertEquals("build a house", board.primary().orElseThrow().text());
        assertEquals(3, board.background().size());
    }

    @Test
    void fourthBackgroundGoalIsRejectedWithAnError() {
        GoalBoard board = new GoalBoard(3, 3);
        for (int i = 0; i < 3; i++) {
            board.set("bg" + i, List.of(), true, 1);
        }
        GoalResult<Goal> result = board.set("one too many", List.of(), true, 1);
        assertFalse(result.ok());
        assertTrue(result.error().contains("background"), result.error());
    }

    @Test
    void secondPrimaryErrorsAndKeepsTheFirst() {
        GoalBoard board = new GoalBoard(3, 3);
        board.set("first", List.of(), false, 1);
        GoalResult<Goal> result = board.set("second", List.of(), false, 2);
        assertFalse(result.ok());
        assertEquals("first", board.primary().orElseThrow().text());
    }

    @Test
    void lessonsCapAtFiveCutTo160AndIgnoreDuplicates() {
        GoalBoard board = new GoalBoard(3, 3);
        long id = board.set("g", List.of(), false, 1).value().id();
        board.addLesson(id, "x".repeat(400));
        assertEquals(160, board.primary().orElseThrow().lessons().get(0).length());
        board.addLesson(id, "same");
        board.addLesson(id, "same");
        assertEquals(2, board.primary().orElseThrow().lessons().size());
        for (int i = 0; i < 10; i++) {
            board.addLesson(id, "lesson " + i);
        }
        List<String> lessons = board.primary().orElseThrow().lessons();
        assertEquals(5, lessons.size());
        assertEquals("lesson 9", lessons.get(lessons.size() - 1));
    }

    @Test
    void recordReplanBlocksGoalWhenBudgetSpent() {
        GoalBoard board = new GoalBoard(3, 2);
        long id = board.set("g", List.of(), false, 1).value().id();
        assertTrue(board.recordReplan(id));
        assertTrue(board.recordReplan(id));
        assertFalse(board.recordReplan(id));
        assertEquals(GoalStatus.BLOCKED, board.primary().orElseThrow().status());
    }

    @Test
    void reactivatingABlockedGoalGivesItAFreshReplanBudget() {
        GoalBoard board = new GoalBoard(3, 1);
        long id = board.set("g", List.of(), false, 1).value().id();
        assertTrue(board.recordReplan(id));
        assertFalse(board.recordReplan(id));
        assertEquals(GoalStatus.BLOCKED, board.primary().orElseThrow().status());
        board.update(id, Optional.empty(), Optional.empty(), Optional.of(GoalStatus.ACTIVE));
        assertEquals(0, board.primary().orElseThrow().replans());
        assertTrue(board.recordReplan(id), "the budget is available again");
    }

    @Test
    void settingAnActiveGoalToActiveKeepsItsReplanCount() {
        GoalBoard board = new GoalBoard(3, 3);
        long id = board.set("g", List.of(), false, 1).value().id();
        board.recordReplan(id);
        board.update(id, Optional.of("renamed"), Optional.empty(), Optional.of(GoalStatus.ACTIVE));
        assertEquals(1, board.primary().orElseThrow().replans());
    }

    @Test
    void unmetUsesGuardChecksAgainstFacts() {
        GoalBoard board = new GoalBoard(3, 3);
        Criterion logs = new Criterion("12 logs", Optional.of(new Guard.HasItem("minecraft:oak_log", 12)));
        long id = board.set("wood", List.of(logs, text("ask the owner")), false, 1).value().id();
        assertEquals(2, board.unmet(id, items(Map.of("minecraft:oak_log", 5))).size());
        List<Criterion> unmet = board.unmet(id, items(Map.of("minecraft:oak_log", 12)));
        assertEquals(1, unmet.size());
        assertEquals("ask the owner", unmet.get(0).text());
    }

    @Test
    void finishDoneSetsStatusAndFreesThePrimarySlot() {
        GoalBoard board = new GoalBoard(3, 3);
        long id = board.set("g", List.of(), false, 1).value().id();
        GoalResult<Goal> done = board.finish(id, true, "built");
        assertTrue(done.ok());
        assertEquals(GoalStatus.DONE, done.value().status());
        assertTrue(board.primary().isEmpty());
        assertTrue(board.set("next", List.of(), false, 2).ok());
    }

    @Test
    void emptyTextAndTooManyCriteriaAreRejected() {
        GoalBoard board = new GoalBoard(3, 3);
        assertFalse(board.set("  ", List.of(), false, 1).ok());
        assertFalse(board.set("g", List.of(text("1"), text("2"), text("3"), text("4"), text("5"), text("6"), text("7")),
                false, 1).ok());
    }

    @Test
    void viewIsEmptyWithoutAGoalAndShowsUnmetOtherwise() {
        GoalBoard board = new GoalBoard(3, 3);
        assertTrue(board.view("none", items(Map.of())).isEmpty());
        board.set("wood", List.of(new Criterion("12 logs", Optional.of(new Guard.HasItem("minecraft:oak_log", 12)))),
                false, 1);
        GoalView view = board.view("running", items(Map.of()));
        assertEquals("wood", view.text());
        assertEquals(List.of("12 logs"), view.unmet());
        assertEquals("running", view.planStatus());
    }
}
