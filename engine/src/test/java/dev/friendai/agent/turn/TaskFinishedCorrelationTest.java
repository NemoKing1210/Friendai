package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TaskFinishedCorrelationTest {
    @Test
    void theLineNamesThePlanAndGoalWhenTheCallerKnowsThem() {
        String line = new BrainEvent.TaskFinished(4, 7, 3, "mine_blocks", true, "mined 8 stone", "").toPromptLine();
        assertTrue(line.contains("task #4"), line);
        assertTrue(line.contains("(plan #7)"), line);
        assertTrue(line.contains("(goal #3)"), line);
    }

    @Test
    void aStandaloneTaskCarriesNoCorrelation() {
        String line = new BrainEvent.TaskFinished(4, "mine_blocks", true, "mined 8 stone", "").toPromptLine();
        assertTrue(!line.contains("plan #") && !line.contains("goal #"), line);
    }
}
