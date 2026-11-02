package dev.friendai.agent.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.execution.queue.Guard;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GoalSaveTest {
    @Test
    void roundTripKeepsGoalsCriteriaGuardsAndLessons() {
        GoalBoard board = new GoalBoard(3, 3);
        long id = board.set(
                        "wood",
                        List.of(
                                new Criterion("12 logs", Optional.of(new Guard.HasItem("minecraft:oak_log", 12))),
                                new Criterion("tell the owner", Optional.empty())),
                        false,
                        7)
                .value()
                .id();
        board.linkPlan(id, 42);
        board.addLesson(id, "no axe");
        board.recordReplan(id);
        board.set("bg", List.of(), true, 8);

        GoalBoard back = GoalSave.decode(GoalSave.encode(board), 3, 3).orElseThrow();
        Goal goal = back.primary().orElseThrow();
        assertEquals("wood", goal.text());
        assertEquals(42, goal.planId());
        assertEquals(1, goal.replans());
        assertEquals(List.of("no axe"), goal.lessons());
        assertEquals(
                new Guard.HasItem("minecraft:oak_log", 12),
                goal.criteria().get(0).check().orElseThrow());
        assertTrue(goal.criteria().get(1).check().isEmpty());
        assertEquals(1, back.background().size());
        assertTrue(back.set("later", List.of(), true, 9).ok(), "ids and counters survive");
    }

    @Test
    void brokenJsonDecodesToEmpty() {
        assertTrue(GoalSave.decode("{", 3, 3).isEmpty());
        assertTrue(GoalSave.decode(null, 3, 3).isEmpty());
        assertTrue(GoalSave.decode("not a save", 3, 3).isEmpty());
    }

    @Test
    void unknownVersionDecodesToEmpty() {
        assertTrue(GoalSave.decode("{\"version\":9}", 3, 3).isEmpty());
    }

    @Test
    void oversizedInputDecodesToEmpty() {
        String big = "{\"goals\":[],\"pad\":\"" + "x".repeat(40_000) + "\"}";
        assertTrue(GoalSave.decode(big, 3, 3).isEmpty());
    }

    @Test
    void roundTripKeepsTheDeadlineAndDefaultsItToZero() {
        GoalBoard board = new GoalBoard(3, 3);
        long due = 4_000 + 30 * GoalDeadline.TICKS_PER_MINUTE;
        board.set("wood", List.of(), false, 4_000, due);
        board.set("bg", List.of(), true, 4_000);

        GoalBoard back = GoalSave.decode(GoalSave.encode(board), 3, 3).orElseThrow();
        assertEquals(due, back.primary().orElseThrow().deadlineTick());
        assertEquals(0L, back.background().get(0).deadlineTick());
        assertTrue(GoalSave.encode(board).contains("\"deadline\":" + due));
        assertTrue(GoalSave.encode(board).contains("\"deadline\":0"), "a goal without a deadline still writes it");
    }

    @Test
    void aSaveFromBeforeTheDeadlineExistedDecodesToNoDeadline() {
        String old = "{\"nextId\":2,\"goals\":["
                + "{\"id\":1,\"text\":\"wood\",\"status\":\"ACTIVE\",\"plan\":0,\"replans\":0,"
                + "\"created\":7,\"background\":false,\"lessons\":[],\"criteria\":[{\"text\":\"12 logs\"}]}]}";
        GoalBoard back = GoalSave.decode(old, 3, 3).orElseThrow();
        assertEquals("wood", back.primary().orElseThrow().text());
        assertEquals(0L, back.primary().orElseThrow().deadlineTick());

        String negative = old.replace("\"created\":7,", "\"created\":7,\"deadline\":-50,");
        assertEquals(
                0L,
                GoalSave.decode(negative, 3, 3)
                        .orElseThrow()
                        .primary()
                        .orElseThrow()
                        .deadlineTick(),
                "a broken deadline never becomes a due date");
    }
}
