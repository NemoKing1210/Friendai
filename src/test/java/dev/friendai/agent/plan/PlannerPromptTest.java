package dev.friendai.agent.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.goal.Criterion;
import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.debug.SecretRedactor;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlannerPromptTest {
    private static Goal goal() {
        return new Goal(1, "iron armor", List.of(new Criterion("full set worn", Optional.empty())),
                GoalStatus.ACTIVE, 0, 0, List.of(), 0, false);
    }

    @Test
    void promptContainsGoalLessonsFailureAndToolDigest() {
        PlannerInput in = new PlannerInput(goal(), "Position: 1 2 3", "- jump {}", "iron near the river",
                Optional.of("job #1 jump"), List.of("no axe"), "missing 3 minecraft:iron_ingot");
        String user = PlannerPrompt.user(in, text -> text);
        for (String part : List.of("Goal: iron armor", "full set worn", "Position: 1 2 3", "iron near the river",
                "job #1 jump", "no axe", "missing 3 minecraft:iron_ingot", "Tools:\n- jump {}")) {
            assertTrue(user.contains(part), "missing: " + part + "\n" + user);
        }
    }

    @Test
    void emptyOptionalSectionsAreLeftOut() {
        PlannerInput in = new PlannerInput(goal(), "w", "t", "", Optional.empty(), List.of(), "");
        String user = PlannerPrompt.user(in, text -> text);
        assertFalse(user.contains("Lessons so far"));
        assertFalse(user.contains("What just failed"));
        assertFalse(user.contains("Current queue"));
        assertFalse(user.contains("Known places"));
    }

    @Test
    void secretsAreRedactedFromEverythingTheModelSees() {
        PlannerInput in = new PlannerInput(goal(), "key sk-test-123 leaked", "t", "", Optional.empty(), List.of(), "");
        String user = PlannerPrompt.user(in, SecretRedactor.of(() -> List.of("sk-test-123")));
        assertFalse(user.contains("sk-test-123"), user);
        assertTrue(user.contains("***"));
    }

    @Test
    void systemPromptDemandsJsonAndForbidsRepeatingFailures() {
        String system = PlannerPrompt.system();
        assertTrue(system.contains("one JSON object"));
        assertTrue(system.contains("do not repeat an approach that failed"));
    }
}
