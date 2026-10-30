package dev.friendai.agent.plan;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.agent.llm.model.*;
import dev.friendai.foundation.diagnostics.SecretRedactor;
import dev.friendai.testutil.FakeGateway;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class PlannerFailuresTest {
    private static final String GOOD = "{\"steps\":[{\"tool\":\"jump\"}]}";

    private static PlannerInput input() {
        return new PlannerInput(
                new Goal(1, "hop", List.of(), GoalStatus.ACTIVE, 0, 0, List.of(), 0, false),
                "world",
                "jump",
                "",
                Optional.empty(),
                List.of(),
                "",
                Map.of(
                        "jump",
                        JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}")
                                .getAsJsonObject()),
                Set.of("jump"));
    }

    private static PlannerParser.Parsed parse(String text) {
        return PlannerParser.parse(text, input().schemas(), input().allTools(), 12, 8192, 49152);
    }

    @Test
    void replyShapesHaveDistinctFailuresAndFencesStillWork() {
        assertTrue(parse("```json\n" + GOOD + "\n```").ok());
        assertEquals(PlanFailure.Code.EMPTY_REPLY, parse("").failure().code());
        assertEquals(PlanFailure.Code.NO_JSON, parse("Please jump").failure().code());
        var broken = parse("{\"steps\": [}").failure();
        assertEquals(PlanFailure.Code.BROKEN_JSON, broken.code());
        assertTrue(broken.detail().contains("line") && broken.detail().contains("column"), broken.detail());
        assertEquals(
                PlanFailure.Code.STEPS_INVALID,
                parse("{\"steps\":[{\"tool\":\"missing\"}]}").failure().code());
        assertTrue(
                parse("{\"steps\":[{\"tool\":\"missing\"}]}").failure().detail().contains("step 1"));
        assertEquals(
                PlanFailure.Code.TOO_LARGE,
                parse(" ".repeat(PlannerParser.MAX_REPLY_CHARS + 1)).failure().code());
    }

    @Test
    void malformedOuterPlanCannotBeSalvagedAsInnerAction() {
        assertEquals(
                PlanFailure.Code.BROKEN_JSON,
                parse("{\"steps\":[{\"tool\":\"jump\"}]").failure().code());
    }

    @Test
    void strictJsonRejectsUnquotedNamesAndTrailingComma() {
        assertEquals(
                PlanFailure.Code.BROKEN_JSON,
                parse("{steps:[{tool:'jump'}]}").failure().code());
        assertEquals(
                PlanFailure.Code.BROKEN_JSON,
                parse("{\"steps\":[{\"tool\":\"jump\"}],}").failure().code());
    }

    @Test
    void retriesCarryRedactedEvidenceAndSpecificFailure() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(FakeGateway.text("secret-key prose"));
        gateway.enqueue(FakeGateway.text(GOOD));
        List<LlmPlanner.Event> events = new ArrayList<>();
        var planner = new LlmPlanner(
                () -> gateway, SecretRedactor.of(() -> List.of("secret-key")), 30, 12, 8192, 49152, 2, events::add);
        planner.propose(input()).get();
        var history = gateway.requests.get(1).messages();
        assertEquals(Role.ASSISTANT, history.get(1).role());
        assertEquals("*** prose", history.get(1).text());
        assertTrue(history.get(2).text().contains("NO_JSON"));
        assertTrue(events.stream().noneMatch(e -> e.toString().contains("secret-key")));
        assertEquals(PlanFailure.Code.NO_JSON, events.get(0).failure().code());
    }

    @Test
    void truncationWinsOverEvenAValidObjectAndIncreasesOutputRoom() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(new LlmResponse(GOOD, List.of(), StopReason.MAX_TOKENS, new Usage(1, 1)));
        gateway.enqueue(FakeGateway.text(GOOD));
        List<LlmPlanner.Event> events = new ArrayList<>();
        new LlmPlanner(() -> gateway, s -> s, 30, 12, 8192, 49152, 2, events::add)
                .propose(input())
                .get();
        assertEquals(PlanFailure.Code.TRUNCATED, events.getFirst().failure().code());
        assertTrue(gateway.jsonRequests.get(1).outputMultiplier()
                > gateway.jsonRequests.get(0).outputMultiplier());
        assertTrue(gateway.jsonRequests.size() == gateway.requests.size());
    }

    @Test
    void transportFailureDoesNotRetryOrLeakCauseMessages() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueueFailure(new CompletionException(new IllegalStateException("secret")));
        var e = assertThrows(
                ExecutionException.class,
                () -> new LlmPlanner(() -> gateway, s -> s, 30, 12, 8192, 49152, 4, x -> {})
                        .propose(input())
                        .get());
        var failure = ((PlannerException) e.getCause()).failure();
        assertEquals(PlanFailure.Code.REQUEST_FAILED, failure.code());
        assertEquals("IllegalStateException", failure.detail());
        assertEquals(1, gateway.requests.size());
        assertFalse(e.getCause().getMessage().contains("secret"));
    }

    @Test
    void configuredAttemptCountAndShorterPlanRepairAreHonored() {
        FakeGateway gateway = new FakeGateway();
        for (int i = 0; i < 3; i++)
            gateway.enqueue(FakeGateway.text("{\"steps\":[{\"tool\":\"jump\"},{\"tool\":\"jump\"}]}"));
        var e = assertThrows(
                ExecutionException.class,
                () -> new LlmPlanner(() -> gateway, s -> s, 30, 1, 8192, 49152, 3, x -> {})
                        .propose(input())
                        .get());
        assertEquals(3, ((PlannerException) e.getCause()).failure().attempt());
        assertEquals(
                PlanFailure.Code.TOO_LARGE,
                ((PlannerException) e.getCause()).failure().code());
        assertTrue(gateway.requests.get(1).messages().getLast().text().contains("shorter"));
        assertEquals(3, gateway.requests.size());
    }

    @Test
    void ownerMessagesAreLocalizedAndNeverIncludeInternalDetails() {
        for (var code : PlanFailure.Code.values()) {
            var message = PlanFailure.ownerMessage(new CompletionException(new ExecutionException(
                    new PlannerException(new PlanFailure(code, "dev.secret.Exception secret", 2)))));
            assertTrue(message.key().startsWith("friendai.freetime.no_plan."));
            assertTrue(message.args().stream()
                    .noneMatch(a ->
                            a.toString().contains("Exception") || a.toString().contains("dev.secret")));
        }
    }

    @Test
    void everyRejectedShapeCarriesItsEvidenceIntoTheRepairAndLog() throws Exception {
        String[] replies = {"prose", "", "{\"steps\":", "{\"steps\":[}"};
        PlanFailure.Code[] codes = {
            PlanFailure.Code.NO_JSON,
            PlanFailure.Code.EMPTY_REPLY,
            PlanFailure.Code.TRUNCATED,
            PlanFailure.Code.BROKEN_JSON
        };
        for (int i = 0; i < replies.length; i++) {
            FakeGateway gateway = new FakeGateway();
            gateway.enqueue(new LlmResponse(
                    replies[i], List.of(), i == 2 ? StopReason.MAX_TOKENS : StopReason.END, new Usage(0, 0)));
            gateway.enqueue(FakeGateway.text(GOOD));
            List<LlmPlanner.Event> events = new ArrayList<>();
            new LlmPlanner(() -> gateway, s -> s, 30, 12, 8192, 49152, 2, events::add)
                    .propose(input())
                    .get();
            assertEquals(codes[i], events.getFirst().failure().code());
            var history = gateway.requests.get(1).messages();
            assertEquals(replies[i], history.get(1).text());
            assertTrue(history.getLast()
                    .text()
                    .contains(events.getFirst().failure().detail()));
            assertEquals("retry", events.getFirst().disposition());
            assertEquals("validated", events.getLast().disposition());
        }
    }

    @Test
    void secretCrossingDiagnosticLimitIsRedactedBeforeTruncation() throws Exception {
        String secret = "s".repeat(2200);
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(FakeGateway.text("{\"steps\":[{\"tool\":\"" + secret + "\"}]}"));
        gateway.enqueue(FakeGateway.text(GOOD));
        List<LlmPlanner.Event> events = new ArrayList<>();
        new LlmPlanner(() -> gateway, SecretRedactor.of(() -> List.of(secret)), 30, 12, 8192, 49152, 2, events::add)
                .propose(input())
                .get();
        assertFalse(events.getFirst().failure().detail().contains("s".repeat(20)));
        assertTrue(events.getFirst().failure().detail().contains("***"));
        assertTrue(gateway.requests.get(1).messages().stream()
                .noneMatch(m -> m.text().contains("s".repeat(20))));
    }

    @Test
    void rejectedStepIsTranslatedInsteadOfEchoingQueueDiagnostic() {
        var message = PlanFailure.ownerMessage(new PlannerException(
                new PlanFailure(PlanFailure.Code.STEPS_INVALID, "step 3: unknown tool 'private_tool'", 1)));
        assertEquals(
                dev.friendai.foundation.text.UserMessage.of("friendai.freetime.no_plan.step", 3),
                message.args().getFirst());
    }
}
