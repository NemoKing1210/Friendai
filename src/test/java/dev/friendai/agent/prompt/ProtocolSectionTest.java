package dev.friendai.agent.prompt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.goal.GoalView;
import dev.friendai.brain.Persona;
import dev.friendai.brain.PromptBuilder;
import dev.friendai.brain.TurnKind;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProtocolSectionTest {
    private static String prompt(AccessLevel level, int budget) {
        return PromptBuilder.system(new Persona("F", "d", "auto"), "Position: 1 2 3",
                new Caller(UUID.randomUUID(), "S", level), "", TurnKind.PLAYER, GoalView.EMPTY, budget, List.of());
    }

    @Test
    void alwaysPresentForEveryRole() {
        for (AccessLevel level : AccessLevel.values()) {
            String s = prompt(level, 0);
            assertTrue(s.contains("Working method:"), level + "\n" + s);
            assertTrue(s.contains("Never claim a result the facts do not show"), level + "\n" + s);
        }
    }

    @Test
    void neverCutEvenUnderTheTightestBudget() {
        String s = prompt(AccessLevel.OWNER, 1);
        assertTrue(s.contains("Working method:"), s);
        assertTrue(s.contains("Speech:") && s.contains("You are F"), s);
    }
}
