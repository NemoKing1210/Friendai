package dev.friendai.agent.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.queue.Guard;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GoalSaveTest {
    @Test
    void roundTripKeepsGoalsCriteriaGuardsAndLessons() {
        GoalBoard board = new GoalBoard(3, 3);
        long id = board.set("wood",
                List.of(new Criterion("12 logs", Optional.of(new Guard.HasItem("minecraft:oak_log", 12))),
                        new Criterion("tell the owner", Optional.empty())),
                false, 7).value().id();
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
        assertEquals(new Guard.HasItem("minecraft:oak_log", 12), goal.criteria().get(0).check().orElseThrow());
        assertTrue(goal.criteria().get(1).check().isEmpty());
        assertEquals(1, back.background().size());
        assertTrue(back.set("later", List.of(), true, 9).ok(), "ids and counters survive");
    }

    @Test
    void brokenJsonDecodesToEmpty() {
        assertTrue(GoalSave.decode("goals:v1:{", 3, 3).isEmpty());
        assertTrue(GoalSave.decode(null, 3, 3).isEmpty());
        assertTrue(GoalSave.decode("not a save", 3, 3).isEmpty());
    }

    @Test
    void unknownVersionDecodesToEmpty() {
        assertTrue(GoalSave.decode("goals:v9:{\"goals\":[]}", 3, 3).isEmpty());
    }

    @Test
    void oversizedInputDecodesToEmpty() {
        String big = "goals:v1:{\"goals\":[],\"pad\":\"" + "x".repeat(40_000) + "\"}";
        assertTrue(GoalSave.decode(big, 3, 3).isEmpty());
    }
}
