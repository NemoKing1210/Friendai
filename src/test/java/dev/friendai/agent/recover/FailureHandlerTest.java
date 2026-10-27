package dev.friendai.agent.recover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.goal.Criterion;
import dev.friendai.agent.goal.GoalBoard;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.queue.Guard;
import dev.friendai.queue.GuardFacts;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FailureHandlerTest {
    private static final String MISSING = "missing materials: 3 × minecraft:iron_ingot";
    private final FailureHandler handler = new FailureHandler(new RecoveryAdvisor());

    private static GuardFacts logs(int count) {
        return new GuardFacts() {
            @Override public int countItems(String id) { return count; }
            @Override public int emptySlots() { return 5; }
            @Override public float healthFraction() { return 1f; }
            @Override public int ownerDistance() { return 0; }
            @Override public int countBlocks(String id, int radius) { return 0; }
            @Override public String dimension() { return "minecraft:overworld"; }
        };
    }

    private static GoalBoard boardWithPlan(int maxReplans, long plan) {
        GoalBoard board = new GoalBoard(3, maxReplans);
        long id = board.set("armor", List.of(), false, 1).value().id();
        board.linkPlan(id, plan);
        return board;
    }

    @Test
    void failureCarriesFactsAndDiagnosis() {
        GoalBoard board = boardWithPlan(3, 7);
        String extra = handler.onFailure(board, 7, "build_structure", MISSING, "facts: -3 minecraft:cobblestone");
        assertTrue(extra.contains("facts: -3 minecraft:cobblestone"), extra);
        assertTrue(extra.contains("diagnosis: MISSING_ITEM missing 3 minecraft:iron_ingot; try: obtain_item minecraft:iron_ingot"), extra);
        assertFalse(extra.contains("replan budget spent"), extra);
    }

    @Test
    void repeatedFailureDoesNotRepeatTheSameRemedyAndAddsLessons() {
        GoalBoard board = boardWithPlan(3, 7);
        handler.onFailure(board, 7, "build_structure", MISSING, "");
        String second = handler.onFailure(board, 7, "build_structure", MISSING, "");
        assertFalse(second.contains("try: obtain_item minecraft:iron_ingot"), second);
        assertTrue(second.contains("ask the player for help"), second);
        List<String> lessons = board.primary().orElseThrow().lessons();
        assertTrue(lessons.contains("missing 3 minecraft:iron_ingot"), lessons.toString());
        assertTrue(lessons.contains("tried: obtain_item minecraft:iron_ingot"), lessons.toString());
        assertEquals(2, lessons.size(), "a repeated failure adds each lesson once");
    }

    @Test
    void budgetSpentBlocksTheGoalAndAsksThePlayer() {
        GoalBoard board = boardWithPlan(1, 7);
        String first = handler.onFailure(board, 7, "go_to", "can't reach 1 2 3", "");
        assertFalse(first.contains(FailureHandler.BUDGET_SPENT), first);
        String second = handler.onFailure(board, 7, "go_to", "can't reach 1 2 3", "");
        assertTrue(second.contains(FailureHandler.BUDGET_SPENT), second);
        assertEquals(GoalStatus.BLOCKED, board.primary().orElseThrow().status());
    }

    @Test
    void directTaskFailureHasNoBudgetAndNeverTouchesTheBoard() {
        GoalBoard board = boardWithPlan(0, 7);
        String extra = handler.onFailure(board, 0, "go_to", "can't reach 1 2 3", "");
        assertTrue(extra.contains("diagnosis: UNREACHABLE"), extra);
        assertEquals(GoalStatus.ACTIVE, board.primary().orElseThrow().status());
    }

    @Test
    void aStopOrCancelIsNotAFailureAndSpendsNothing() {
        GoalBoard board = boardWithPlan(1, 7);
        for (String text : new String[] {"stopped by request", "cancelled by the LLM", "Cancelled", "stopped"}) {
            assertTrue(FailureHandler.isCancellation(text), text);
            assertEquals("facts: -1 minecraft:stick",
                    handler.onFailure(board, 7, "plan", text, "facts: -1 minecraft:stick"));
        }
        assertEquals(0, board.primary().orElseThrow().replans());
        assertTrue(board.primary().orElseThrow().lessons().isEmpty());
        assertEquals(GoalStatus.ACTIVE, board.primary().orElseThrow().status());
        assertFalse(FailureHandler.isCancellation("can't reach 1 2 3"));
        assertFalse(FailureHandler.isCancellation("the bridge collapsed and the stop sign fell"));
        assertFalse(FailureHandler.isCancellation(null));
    }

    @Test
    void unknownFailureAddsNoDiagnosisAndNoLesson() {
        GoalBoard board = boardWithPlan(3, 7);
        String extra = handler.onFailure(board, 7, "stay", "gremlins", "");
        assertEquals("", extra);
        assertTrue(board.primary().orElseThrow().lessons().isEmpty());
    }

    @Test
    void successListsUnmetCriteriaOrConfirmsAll() {
        GoalBoard board = new GoalBoard(3, 3);
        long id = board.set("wood",
                List.of(new Criterion("12 logs", Optional.of(new Guard.HasItem("minecraft:oak_log", 12)))), false, 1)
                .value().id();
        board.linkPlan(id, 9);
        assertEquals("facts: +5 minecraft:oak_log; unmet: 12 logs",
                handler.onSuccess(board, 9, "facts: +5 minecraft:oak_log", logs(5)));
        assertEquals("all criteria confirmed: call finish_goal", handler.onSuccess(board, 9, "", logs(12)));
    }

    @Test
    void successWithoutALinkedGoalOnlyEchoesFacts() {
        GoalBoard board = new GoalBoard(3, 3);
        assertEquals("facts: +1 minecraft:stick", handler.onSuccess(board, 4, "facts: +1 minecraft:stick", logs(0)));
    }
}
