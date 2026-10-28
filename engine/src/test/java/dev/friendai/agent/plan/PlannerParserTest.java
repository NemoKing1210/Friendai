package dev.friendai.agent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlannerParserTest {
    private static final JsonObject JUMP_SCHEMA = JsonParser.parseString(
                    "{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\"}}}")
            .getAsJsonObject();
    private static final Map<String, JsonObject> SCHEMAS = Map.of("jump", JUMP_SCHEMA, "go_to", JUMP_SCHEMA);
    private static final Set<String> ALL = Set.of("jump", "go_to", "trust_player");

    private static PlannerParser.Parsed parse(String text) {
        return PlannerParser.parse(text, SCHEMAS, ALL, 12, 8192, 49152);
    }

    @Test
    void parsesAValidProposalWithRationale() {
        PlannerParser.Parsed p = parse("{\"rationale\":\"jump twice\",\"expected_outcome\":\"hopped\","
                + "\"steps\":[{\"tool\":\"jump\",\"args\":\"{\\\"count\\\":2}\"},{\"tool\":\"go_to\",\"after\":[1]}]}");
        assertTrue(p.ok(), p.errors().toString());
        PlanProposal proposal = p.proposal().orElseThrow();
        assertEquals(2, proposal.steps().size());
        assertEquals("jump twice", proposal.rationale());
        assertEquals("hopped", proposal.expectedOutcome());
        assertEquals(2, proposal.stepsJson().size());
    }

    @Test
    void objectArgsAreAcceptedAndNormalizedToAString() {
        PlannerParser.Parsed p = parse("{\"steps\":[{\"tool\":\"jump\",\"args\":{\"count\":3}}]}");
        assertTrue(p.ok(), p.errors().toString());
        assertTrue(p.proposal()
                .orElseThrow()
                .stepsJson()
                .get(0)
                .getAsJsonObject()
                .get("args")
                .getAsJsonPrimitive()
                .isString());
        assertEquals(
                3, p.proposal().orElseThrow().steps().get(0).args().get("count").getAsInt());
    }

    @Test
    void stripsCodeFencesAndChat() {
        PlannerParser.Parsed p = parse("Sure!\n```json\n{\"steps\":[{\"tool\":\"jump\"}]}\n```\nHope it helps.");
        assertTrue(p.ok(), p.errors().toString());
    }

    @Test
    void aBareArrayOfStepsIsAccepted() {
        PlannerParser.Parsed p = parse("[{\"tool\":\"jump\"},{\"tool\":\"go_to\"}]");
        assertTrue(p.ok(), p.errors().toString());
        assertEquals(2, p.proposal().orElseThrow().steps().size());
    }

    @Test
    void anExampleBeforeThePlanDoesNotHideIt() {
        PlannerParser.Parsed p = parse("Args look like {\"count\": 2}; the plan is {\"steps\":[{\"tool\":\"jump\"}]}");
        assertTrue(p.ok(), p.errors().toString());
        assertEquals(1, p.proposal().orElseThrow().steps().size());
    }

    @Test
    void bracesInProseAfterTheObjectDoNotBreakIt() {
        PlannerParser.Parsed p = parse("{\"steps\":[{\"tool\":\"jump\"}]}\nArgs look like {\"count\": 2}");
        assertTrue(p.ok(), p.errors().toString());
        assertEquals(1, p.proposal().orElseThrow().steps().size());
    }

    @Test
    void invalidJsonReturnsErrors() {
        assertFalse(parse("I think you should jump").ok());
        assertFalse(parse("{\"steps\": 5}").ok());
        assertFalse(parse("").ok());
        assertFalse(parse(null).ok());
    }

    @Test
    void unknownToolIsAnError() {
        PlannerParser.Parsed p = parse("{\"steps\":[{\"tool\":\"teleport\"}]}");
        assertFalse(p.ok());
        assertTrue(
                p.errors().get(0).contains("unknown tool 'teleport'"),
                p.errors().toString());
    }

    @Test
    void toolOutsideTheRequestersAllowedSetIsAnError() {
        PlannerParser.Parsed p = parse("{\"steps\":[{\"tool\":\"trust_player\"}]}");
        assertFalse(p.ok());
        assertTrue(
                p.errors().get(0).contains("cannot be used in a plan"),
                p.errors().toString());
    }

    @Test
    void stepLimitApplies() {
        StringBuilder steps = new StringBuilder("{\"steps\":[");
        for (int i = 0; i < 13; i++) {
            steps.append(i > 0 ? "," : "").append("{\"tool\":\"jump\"}");
        }
        PlannerParser.Parsed p = parse(steps.append("]}").toString());
        assertFalse(p.ok());
        assertTrue(
                p.errors().get(0).contains("worst-case action count exceeds 12"),
                p.errors().toString());
    }

    @Test
    void emptyStepListIsAnError() {
        assertFalse(parse("{\"steps\":[]}").ok());
    }

    @Test
    void longRationaleIsCut() {
        PlannerParser.Parsed p = parse("{\"rationale\":\"" + "x".repeat(1000) + "\",\"steps\":[{\"tool\":\"jump\"}]}");
        assertEquals(
                PlannerParser.MAX_NOTE, p.proposal().orElseThrow().rationale().length());
    }

    @Test
    void parsedStepsCarryTheListOrder() {
        PlannerParser.Parsed p = parse("{\"steps\":[{\"tool\":\"go_to\"},{\"tool\":\"jump\"}]}");
        assertEquals(
                List.of("go_to", "jump"),
                p.proposal().orElseThrow().steps().stream().map(s -> s.tool()).toList());
    }

    @Test
    void nestedActionObjectArgumentsNormalizeAndPreserveThePlanTree() {
        PlannerParser.Parsed parsed = parse("""
                {"steps":[
                  {"when":{"type":"fluid_ahead"},"then":[{"tool":"jump","args":{"count":2}}],
                   "else":[{"repeat":{"steps":[{"tool":"go_to","args":{"count":3}}],
                     "until":{"type":"time_elapsed","ticks":20},"max_iterations":2}}]}
                ]}
                """);
        assertTrue(parsed.ok(), parsed.errors().toString());
        var proposal = parsed.proposal().orElseThrow();
        assertEquals(2, proposal.steps().size());
        assertTrue(proposal.stepsJson()
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("then")
                .get(0)
                .getAsJsonObject()
                .get("args")
                .getAsJsonPrimitive()
                .isString());
        assertTrue(proposal.plan().nodes().getFirst() instanceof dev.friendai.execution.queue.PlanNode.Conditional);
    }

    @Test
    void nestedDisallowedToolHasAPathQualifiedError() {
        PlannerParser.Parsed parsed = parse("""
                {"steps":[{"when":{"type":"fluid_ahead"},"then":[{"tool":"trust_player"}]}]}
                """);
        assertFalse(parsed.ok());
        assertTrue(
                parsed.errors().stream()
                        .anyMatch(error ->
                                error.contains("step 1 → then #1") && error.contains("cannot be used in a plan")),
                parsed.errors().toString());
    }
}
