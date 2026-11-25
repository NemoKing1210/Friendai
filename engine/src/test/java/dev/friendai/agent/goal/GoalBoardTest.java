package dev.friendai.agent.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.prompt.MissionText;
import dev.friendai.execution.queue.Guard;
import dev.friendai.execution.queue.GuardFacts;
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
            @Override
            public int countItems(String id) {
                return items.getOrDefault(id, 0);
            }

            @Override
            public int emptySlots() {
                return 10;
            }

            @Override
            public float healthFraction() {
                return 1f;
            }

            @Override
            public int ownerDistance() {
                return 0;
            }

            @Override
            public int countBlocks(String id, int radius) {
                return 0;
            }

            @Override
            public String dimension() {
                return "minecraft:overworld";
            }
        };
    }

    @Test
    void setsPrimaryThenBackgroundUpToThree() {
        GoalBoard board = new GoalBoard(3, 3);
        assertTrue(
                board.set("build a house", List.of(text("roof done")), false, 1).ok());
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
    void releasingAnEndedPlanFreesOnlyTheGoalThatServedIt() {
        GoalBoard board = new GoalBoard(3, 3);
        Criterion logs = new Criterion("12 logs", Optional.of(new Guard.HasItem("minecraft:oak_log", 12)));
        long primary = board.set("wood", List.of(logs), false, 1).value().id();
        long background = board.set("home", List.of(), true, 1).value().id();
        board.linkPlan(primary, 7);
        board.linkPlan(background, 8);
        board.recordReplan(primary);

        board.releasePlan(7);

        Goal freed = board.find(primary).orElseThrow();
        assertEquals(0, freed.planId(), "the ended plan no longer pins the goal");
        assertEquals(GoalStatus.ACTIVE, freed.status(), "the goal stays open for the next plan");
        assertEquals(1, freed.criteria().size(), "the criteria the next plan must satisfy stay");
        assertEquals(1, freed.replans(), "the budget already spent is not refunded");
        assertEquals(8, board.find(background).orElseThrow().planId(), "another goal keeps its own plan");
    }

    @Test
    void releasingAPlanNothingFollowsLeavesTheBoardAlone() {
        GoalBoard board = new GoalBoard(3, 3);
        long id = board.set("g", List.of(), false, 1).value().id();
        board.linkPlan(id, 7);

        board.releasePlan(9);
        board.releasePlan(0);

        assertEquals(7, board.primary().orElseThrow().planId());
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
        board.update(id, Optional.empty(), Optional.empty(), Optional.of(GoalStatus.ACTIVE), Optional.empty());
        assertEquals(0, board.primary().orElseThrow().replans());
        assertTrue(board.recordReplan(id), "the budget is available again");
    }

    @Test
    void settingAnActiveGoalToActiveKeepsItsReplanCount() {
        GoalBoard board = new GoalBoard(3, 3);
        long id = board.set("g", List.of(), false, 1).value().id();
        board.recordReplan(id);
        board.update(id, Optional.of("renamed"), Optional.empty(), Optional.of(GoalStatus.ACTIVE), Optional.empty());
        assertEquals(1, board.primary().orElseThrow().replans());
    }

    @Test
    void unmetUsesGuardChecksAgainstFacts() {
        GoalBoard board = new GoalBoard(3, 3);
        Criterion logs = new Criterion("12 logs", Optional.of(new Guard.HasItem("minecraft:oak_log", 12)));
        long id = board.set("wood", List.of(logs, text("ask the owner")), false, 1)
                .value()
                .id();
        assertEquals(2, board.unmet(id, items(Map.of("minecraft:oak_log", 5))).size());
        List<Criterion> unmet = board.unmet(id, items(Map.of("minecraft:oak_log", 12)));
        assertEquals(1, unmet.size());
        assertEquals("ask the owner", unmet.get(0).text());
    }

    @Test
    void finishDoneSetsStatusAndFreesThePrimarySlot() {
        GoalBoard board = new GoalBoard(3, 3);
        long id = board.set("g", List.of(), false, 1).value().id();
        GoalResult<GoalBoard.Finished> done = board.finish(id, true, "built");
        assertTrue(done.ok());
        assertEquals(GoalStatus.DONE, done.value().goal().status());
        assertEquals("built", done.value().reason());
        assertTrue(board.primary().isEmpty());
        assertTrue(board.set("next", List.of(), false, 2).ok());
    }

    @Test
    void emptyTextAndTooManyCriteriaAreRejected() {
        GoalBoard board = new GoalBoard(3, 3);
        assertFalse(board.set("  ", List.of(), false, 1).ok());
        assertFalse(board.set(
                        "g",
                        List.of(text("1"), text("2"), text("3"), text("4"), text("5"), text("6"), text("7")),
                        false,
                        1)
                .ok());
    }

    @Test
    void viewIsEmptyWithoutAGoalAndShowsUnmetOtherwise() {
        GoalBoard board = new GoalBoard(3, 3);
        assertTrue(board.view("none", items(Map.of()), List.of(), 1).isEmpty());
        board.set(
                "wood",
                List.of(new Criterion("12 logs", Optional.of(new Guard.HasItem("minecraft:oak_log", 12)))),
                false,
                1);
        GoalView view = board.view("running", items(Map.of()), List.of("mine_blocks"), 1);
        assertEquals("wood", view.text());
        assertEquals(List.of("12 logs"), view.unmet());
        assertEquals("running", view.planStatus());
        assertEquals(RiskClass.CAREFUL, view.risk());
        assertFalse(view.consulted());
    }

    @Test
    void theViewCarriesTheGoalIdAndWhetherItsWorkIsQueued() {
        GoalBoard board = new GoalBoard(3, 3);
        long id = board.set(
                        "wood",
                        List.of(new Criterion("12 logs", Optional.of(new Guard.HasItem("minecraft:oak_log", 12)))),
                        false,
                        1)
                .value()
                .id();
        GoalView idle = board.view("running", items(Map.of()), List.of(), 1);
        assertEquals(id, idle.goalId());
        assertFalse(idle.planQueued());
        assertTrue(board.view("running", items(Map.of()), List.of(), 1, true).planQueued());
    }

    @Test
    void viewDerivesTheRiskFromThePlansTools() {
        GoalBoard board = new GoalBoard(3, 3);
        board.set("clear the yard", List.of(), false, 1);
        GoalView careful = board.view("running", items(Map.of()), List.of("go_to", "mine_blocks"), 1);
        assertEquals(RiskClass.CAREFUL, careful.risk());
        GoalView destructive = board.view("running", items(Map.of()), List.of("go_to", "clear_area"), 1);
        assertEquals(RiskClass.DESTRUCTIVE, destructive.risk());
        assertTrue(destructive.consulted());
        assertEquals(
                RiskClass.SAFE,
                board.view("running", items(Map.of()), List.of(), 1).risk());
    }

    @Test
    void setStoresTheDeadlineAndTheViewCountsItDown() {
        GoalBoard board = new GoalBoard(3, 3);
        long due = 1_000 + 5 * GoalDeadline.TICKS_PER_MINUTE;
        board.set("bring logs", List.of(), false, 1_000, due);
        assertEquals(due, board.primary().orElseThrow().deadlineTick());
        assertEquals(
                "due in 5 minutes",
                board.view("", items(Map.of()), List.of(), 1_000).deadline());
        assertEquals(
                "overdue by 2 minutes",
                board.view("", items(Map.of()), List.of(), due + 2 * GoalDeadline.TICKS_PER_MINUTE)
                        .deadline());
        assertTrue(MissionText.render(board.view("", items(Map.of()), List.of(), 1_000))
                .contains("- Goal: bring logs (risk: safe; due in 5 minutes)"));
    }

    @Test
    void aGoalWithoutADeadlineShowsNone() {
        GoalBoard board = new GoalBoard(3, 3);
        board.set("bring logs", List.of(), false, 1_000);
        assertEquals(0L, board.primary().orElseThrow().deadlineTick());
        assertEquals("", board.view("", items(Map.of()), List.of(), 1_000).deadline());
        assertTrue(MissionText.render(board.view("", items(Map.of()), List.of(), 1_000))
                .contains("- Goal: bring logs (risk: safe)"));
    }

    @Test
    void updateSetsKeepsAndClearsTheDeadline() {
        GoalBoard board = new GoalBoard(3, 3);
        long id = board.set("bring logs", List.of(), false, 1_000).value().id();
        long due = 1_000 + 60 * GoalDeadline.TICKS_PER_MINUTE;
        board.update(id, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(due));
        assertEquals(due, board.primary().orElseThrow().deadlineTick());
        board.update(id, Optional.of("bring planks"), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(due, board.primary().orElseThrow().deadlineTick(), "an omitted deadline is unchanged");
        board.update(id, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(0L));
        assertEquals(0L, board.primary().orElseThrow().deadlineTick());
    }

    @Test
    void aFinishedGoalStaysReadableWhileItIsKept() {
        GoalBoard board = new GoalBoard(3, 3);
        long done = board.set("bring logs", List.of(), false, 10).value().id();
        long dropped = board.set("dig", List.of(), true, 11).value().id();
        board.finish(done, true, "delivered");
        board.finish(dropped, false, "no pickaxe");

        assertTrue(board.all().isEmpty(), "neither goal is active any more");
        assertTrue(board.find(done).isEmpty());
        assertEquals(
                List.of(GoalStatus.DONE, GoalStatus.ABANDONED),
                board.finished().stream().map(Goal::status).toList());
        assertTrue(board.set("next", List.of(), false, 12).ok(), "an id is never reissued");
        assertTrue(
                board.finished().getFirst().id() < board.primary().orElseThrow().id());
    }

    @Test
    void finishedGoalsAreBoundedOldestFirst() {
        GoalBoard board = new GoalBoard(3, 3);
        for (int i = 0; i < GoalBoard.MAX_FINISHED + 2; i++) {
            long id = board.set("g" + i, List.of(), false, i).value().id();
            board.finish(id, true, "done");
        }
        assertEquals(GoalBoard.MAX_FINISHED, board.finished().size());
        assertEquals("g2", board.finished().getFirst().text());
        assertEquals(
                "g" + (GoalBoard.MAX_FINISHED + 1), board.finished().getLast().text());
    }
}
