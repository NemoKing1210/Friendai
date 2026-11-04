package dev.friendai.server.agent;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.agent.goal.Criterion;
import dev.friendai.agent.goal.GoalBoard;
import dev.friendai.agent.verify.Fact;
import dev.friendai.execution.queue.Guard;
import dev.friendai.features.memory.MemoryBook;
import dev.friendai.features.memory.Subject;
import dev.friendai.foundation.teaching.TeachingContext.OutcomeKind;
import dev.friendai.infrastructure.config.ServerConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoalOutcomeRecorderTest {
    private final UUID owner = UUID.randomUUID();
    private final MemoryBook book = new MemoryBook();
    private final ServerConfig config = ServerConfig.defaults();
    private final GoalOutcomeRecorder recorder = new GoalOutcomeRecorder(() -> config, id -> book);

    @Test
    void aWorldCheckedGoalIsRememberedAsVerifiedWithItsShortestFact() {
        GoalBoard board = new GoalBoard(3, 2);
        long goalId = board.set("Collect 32 oak logs", List.of(checkableCriterion()), false, 100L)
                .value()
                .id();
        recorder.captureCheckability(owner, board);
        recorder.noteFacts(owner, goalId, List.of(new Fact("+32 minecraft:oak_log"), new Fact("+1 oak_log")));

        recorder.remember(owner, "goal:" + goalId, OutcomeKind.GOAL_SUCCESS, "Collected 32 oak logs");

        assertEquals(List.of("goal \"Collected 32 oak logs\" — verified; +1 oak_log"), factTexts());
    }

    @Test
    void anAbandonedGoalIsRememberedAsAbandonedAndUnverified() {
        GoalBoard board = new GoalBoard(3, 2);
        long goalId = board.set("Build a tower", List.of(checkableCriterion()), false, 100L)
                .value()
                .id();
        recorder.captureCheckability(owner, board);
        recorder.noteFacts(owner, goalId, List.of(new Fact("+12 minecraft:stone")));

        recorder.remember(owner, "goal:" + goalId, OutcomeKind.GOAL_FAILURE, "Gave up on the tower");

        assertEquals(
                List.of("goal \"Gave up on the tower\" — abandoned, unverified; +12 minecraft:stone"), factTexts());
    }

    @Test
    void anAbandonedGoalWithNothingVerifiedIsNotRemembered() {
        GoalBoard board = new GoalBoard(3, 2);
        long goalId = board.set("Build a tower", List.of(checkableCriterion()), false, 100L)
                .value()
                .id();
        recorder.captureCheckability(owner, board);

        recorder.remember(owner, "goal:" + goalId, OutcomeKind.GOAL_FAILURE, "Gave up on the tower");

        assertTrue(factTexts().isEmpty());
    }

    @Test
    void aGoalTheWorldCouldNotCheckIsRememberedAsUnverifiedEvenWhenItSucceeded() {
        GoalBoard board = new GoalBoard(3, 2);
        long goalId = board.set(
                        "Make a friend", List.of(new Criterion("someone likes you", Optional.empty())), false, 100L)
                .value()
                .id();
        recorder.captureCheckability(owner, board);

        recorder.remember(owner, "goal:" + goalId, OutcomeKind.GOAL_SUCCESS, "Made a friend");

        assertEquals(List.of("goal \"Made a friend\" — unverified"), factTexts());
    }

    @Test
    void forgettingAnOwnerDropsBothTheCheckabilityAndTheFact() {
        GoalBoard board = new GoalBoard(3, 2);
        long goalId = board.set("Collect 32 oak logs", List.of(checkableCriterion()), false, 100L)
                .value()
                .id();
        recorder.captureCheckability(owner, board);
        recorder.noteFacts(owner, goalId, List.of(new Fact("+32 minecraft:oak_log")));

        recorder.forget(owner);
        recorder.remember(owner, "goal:" + goalId, OutcomeKind.GOAL_SUCCESS, "Collected 32 oak logs");

        assertEquals(List.of("goal \"Collected 32 oak logs\" — unverified"), factTexts());
    }

    @Test
    void forgettingOneOwnerKeepsAnotherOwnersBookkeeping() {
        UUID other = UUID.randomUUID();
        GoalBoard first = new GoalBoard(3, 2);
        long firstId = first.set("Collect 32 oak logs", List.of(checkableCriterion()), false, 100L)
                .value()
                .id();
        GoalBoard second = new GoalBoard(3, 2);
        long secondId = second.set("Collect 8 stone", List.of(checkableCriterion()), false, 100L)
                .value()
                .id();
        recorder.captureCheckability(owner, first);
        recorder.noteFacts(owner, firstId, List.of(new Fact("+32 minecraft:oak_log")));
        recorder.captureCheckability(other, second);
        recorder.noteFacts(other, secondId, List.of(new Fact("+8 minecraft:stone")));

        recorder.forget(owner);
        recorder.remember(other, "goal:" + secondId, OutcomeKind.GOAL_SUCCESS, "Collected 8 stone");
        recorder.remember(owner, "goal:" + firstId, OutcomeKind.GOAL_SUCCESS, "Collected 32 oak logs");

        assertEquals(List.of("goal \"Collected 8 stone\" — verified; +8 minecraft:stone"), factTexts(other));
        assertEquals(List.of("goal \"Collected 32 oak logs\" — unverified"), factTexts(owner));
    }

    @Test
    void aSourceThatIsNotAGoalWritesNothing() {
        recorder.remember(owner, "shift:1", OutcomeKind.GOAL_SUCCESS, "Finished a shift");

        assertTrue(factTexts().isEmpty());
    }

    @Test
    void aDisabledMemoryBookWritesNothing() {
        config.memory.enabled = false;
        GoalBoard board = new GoalBoard(3, 2);
        long goalId = board.set("Collect 32 oak logs", List.of(checkableCriterion()), false, 100L)
                .value()
                .id();
        recorder.captureCheckability(owner, board);

        recorder.remember(owner, "goal:" + goalId, OutcomeKind.GOAL_SUCCESS, "Collected 32 oak logs");

        assertTrue(factTexts().isEmpty());
    }

    private List<String> factTexts() {
        return factTexts(owner);
    }

    private List<String> factTexts(UUID subject) {
        return book.facts(Subject.player(subject)).stream()
                .map(fact -> fact.text())
                .toList();
    }

    private static Criterion checkableCriterion() {
        return new Criterion("the world can check it", Optional.of(new Guard.TimeElapsed(100)));
    }
}
