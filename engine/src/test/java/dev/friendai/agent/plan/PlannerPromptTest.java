package dev.friendai.agent.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.friendai.agent.goal.Criterion;
import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.execution.tool.QueueParser;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.foundation.diagnostics.SecretRedactor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlannerPromptTest {
    private static Goal goal() {
        return new Goal(
                1,
                "iron armor",
                List.of(new Criterion("full set worn", Optional.empty())),
                GoalStatus.ACTIVE,
                0,
                0,
                List.of(),
                0,
                false);
    }

    @Test
    void promptContainsGoalLessonsFailureAndToolDigest() {
        PlannerInput in = PlannerInput.promptOnly(
                goal(),
                "Position: 1 2 3",
                "- jump {}",
                "iron near the river",
                Optional.of("job #1 jump"),
                List.of("no axe"),
                "missing 3 minecraft:iron_ingot");
        String user = PlannerPrompt.user(in, text -> text);
        for (String part : List.of(
                "Goal: iron armor",
                "full set worn",
                "Position: 1 2 3",
                "iron near the river",
                "job #1 jump",
                "no axe",
                "missing 3 minecraft:iron_ingot",
                "Tools:\n- jump {}")) {
            assertTrue(user.contains(part), "missing: " + part + "\n" + user);
        }
    }

    @Test
    void emptyOptionalSectionsAreLeftOut() {
        PlannerInput in = PlannerInput.promptOnly(goal(), "w", "t", "", Optional.empty(), List.of(), "");
        String user = PlannerPrompt.user(in, text -> text);
        assertFalse(user.contains("Lessons so far"));
        assertFalse(user.contains("What just failed"));
        assertFalse(user.contains("Current queue"));
        assertFalse(user.contains("Known places"));
    }

    @Test
    void secretsAreRedactedFromEverythingTheModelSees() {
        PlannerInput in =
                PlannerInput.promptOnly(goal(), "key sk-test-123 leaked", "t", "", Optional.empty(), List.of(), "");
        String user = PlannerPrompt.user(in, SecretRedactor.of(() -> List.of("sk-test-123")));
        assertFalse(user.contains("sk-test-123"), user);
        assertTrue(user.contains("***"));
    }

    @Test
    void systemPromptDemandsJsonAndForbidsRepeatingFailures() {
        String system = PlannerPrompt.system();
        assertTrue(system.contains("one JSON object"));
        assertTrue(system.contains("do not repeat an approach that failed"));
        assertTrue(system.contains("\"type\":\"not\",\"condition\":{\"type\":\"has_item\""));
        assertTrue(system.contains("Never use a block or item id for near."));
        assertTrue(system.contains("Enum values are closed"));
        assertTrue(system.contains("slot is mainhand, offhand, head, chest, legs or feet"));
        assertTrue(system.contains("local or expedition"));
        assertTrue(system.contains("not a station type"));
    }

    @Test
    void systemPromptAsksForTheWholeObjectiveInOnePlan() {
        String system = PlannerPrompt.system();
        assertTrue(system.contains("Cover the whole objective in one plan"), system);
        assertTrue(system.contains("use a composite tool once"), system);
        assertTrue(system.contains("Do not split one materials run across steps."), system);
        assertFalse(system.contains("Keep the plan short and concrete"), "the short-plan advice is gone");
    }

    @Test
    void theAdvertisedNotConditionMatchesTheQueueParserContract() {
        String guarded = "[{\"when\":{\"type\":\"not\",\"condition\":"
                + "{\"type\":\"has_item\",\"item\":\"minecraft:oak_log\",\"count\":16}},"
                + "\"then\":[{\"tool\":\"go_to\",\"args\":\"{}\"}]}]";
        var parsed = QueueParser.parsePlan(
                JsonParser.parseString(guarded).getAsJsonArray(),
                Map.of("go_to", SchemaBuilder.object().build()),
                Set.of("go_to"),
                1,
                8192,
                49152);

        assertTrue(parsed.ok(), parsed.errors().toString());

        var invalid = QueueParser.parsePlan(
                JsonParser.parseString("[{\"when\":{\"not\":{\"has_item\":{\"item\":\"minecraft:oak_log\","
                                + "\"count\":16}}},\"then\":[{\"tool\":\"go_to\",\"args\":\"{}\"}]}]")
                        .getAsJsonArray(),
                Map.of("go_to", SchemaBuilder.object().build()),
                Set.of("go_to"),
                1,
                8192,
                49152);
        assertFalse(invalid.ok());
        assertTrue(
                invalid.errors().stream().anyMatch(error -> error.contains("condition type")),
                invalid.errors().toString());
    }

    @Test
    void factsEarlierTasksVerifiedAreShownButBounded() {
        List<String> verified = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            verified.add("+1 minecraft:item" + i);
        }
        PlannerInput in = new PlannerInput(
                goal(),
                "Position: 1 2 3",
                "- jump {}",
                "",
                Optional.empty(),
                List.of(),
                "",
                java.util.Map.of(),
                java.util.Set.of(),
                verified);
        String user = PlannerPrompt.user(in, text -> text);
        assertTrue(user.contains("Already verified by earlier tasks:"), user);
        assertTrue(user.contains("- +1 minecraft:item1"), user);
        assertTrue(user.contains("- +1 minecraft:item8"), user);
        assertFalse(user.contains("minecraft:item9"), "the list stays bounded: " + user);
    }

    @Test
    void thePendingActionSurvivesInstructionAttachment() {
        PlannerInput in = new PlannerInput(
                goal(),
                "Position: 1 2 3",
                "- jump {}",
                "",
                Optional.empty(),
                List.of(),
                "",
                Map.of(),
                Set.of("jump"),
                List.of(),
                List.of(),
                0,
                9L,
                "fingerprint-9",
                "2/3");

        PlannerInput attached = in.withInstructions(
                List.of(new dev.friendai.agent.prompt.SkillPromptSection("extra", "extra text", 1)), 4096);
        String user = PlannerPrompt.user(attached, text -> text);

        assertTrue(user.contains("Pending action: 2/3"), user);
    }

    @Test
    void aPromptWithoutVerifiedFactsDoesNotClaimAny() {
        String user = PlannerPrompt.user(
                PlannerInput.promptOnly(goal(), "Position: 1 2 3", "- jump {}", "", Optional.empty(), List.of(), ""),
                text -> text);
        assertFalse(user.contains("Already verified"), user);
    }
}
