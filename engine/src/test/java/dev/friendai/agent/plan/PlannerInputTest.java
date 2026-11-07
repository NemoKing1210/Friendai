package dev.friendai.agent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.agent.prompt.SkillPromptSection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlannerInputTest {
    private static PlannerInput input() {
        Goal goal = new Goal(1, "repair", List.of(), GoalStatus.ACTIVE, 0, 0, List.of(), 0, false);
        return new PlannerInput(
                goal,
                "world",
                "tools",
                "knowledge",
                Optional.of("queue"),
                List.of("lesson"),
                "failure",
                Map.of(),
                Set.of("jump"),
                List.of("verified"),
                List.of(),
                0,
                7L,
                "fingerprint-7",
                "1/2");
    }

    @Test
    void withInstructionsKeepsEveryComponentIncludingThePlanIdentityAndPendingPath() {
        PlannerInput base = input();
        PlannerInput attached = base.withInstructions(List.of(new SkillPromptSection("extra", "extra text", 1)), 4096);

        assertEquals(base.goal(), attached.goal());
        assertEquals(base.world(), attached.world());
        assertEquals(base.toolDigest(), attached.toolDigest());
        assertEquals(base.knowledge(), attached.knowledge());
        assertEquals(base.queueState(), attached.queueState());
        assertEquals(base.lessons(), attached.lessons());
        assertEquals(base.failure(), attached.failure());
        assertEquals(base.schemas(), attached.schemas());
        assertEquals(base.allTools(), attached.allTools());
        assertEquals(base.verified(), attached.verified());
        assertEquals(4096, attached.promptBudget());
        assertEquals(1, attached.instructions().size());
        assertEquals(7L, attached.targetPlanId(), "the plan being repaired keeps its id");
        assertEquals("fingerprint-7", attached.targetFingerprint(), "the stale-check fingerprint survives");
        assertEquals("1/2", attached.pendingNodePath(), "the model still learns where the plan stands");
    }
}
