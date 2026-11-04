package dev.friendai.agent.turn;

import static dev.friendai.testutil.FakeGateway.call;
import static dev.friendai.testutil.FakeGateway.calls;
import static dev.friendai.testutil.FakeGateway.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.Role;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.testutil.FakeGateway;
import dev.friendai.testutil.FakeToolExecutor;
import dev.friendai.testutil.ManualScheduler;
import dev.friendai.testutil.RecordingSpeaker;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The common case: the model answers the owner and queues the whole plan in one response, so the turn ends there. A
 * plan needs no extra request to read its tool result; a silent, filtered or failed response keeps the ordinary loop.
 */
class CompanionBrainOneShotTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private FakeGateway gateway;
    private FakeToolExecutor tools;
    private RecordingSpeaker speaker;
    private CompanionBrain brain;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        tools = new FakeToolExecutor();
        speaker = new RecordingSpeaker();
        brain = new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(8, 30, 0, 0),
                () -> gateway,
                tools,
                () -> "state",
                speaker,
                new ManualScheduler(),
                AccessResolver.of(OWNER, new HashSet<UUID>()::contains));
    }

    private static BrainEvent said(String text) {
        return new BrainEvent.PlayerSaid(OWNER, "Steve", text, BrainEvent.Source.CHAT);
    }

    @Test
    void oneResponseWithTextAndAPlanEndsTheTurn() {
        gateway.enqueue(calls("On it.", call("q1", "queue_add")));
        gateway.enqueue(text("second"));

        brain.post(said("mine 3 stone"));

        assertEquals(1, gateway.requests.size(), "the tool result does not need another request");
        assertEquals(List.of("On it."), speaker.said);
        assertFalse(brain.isThinking());
    }

    @Test
    void blankTextWithASuccessfulPlanStillGetsAReply() {
        gateway.enqueue(calls("", call("q1", "queue_add")));
        gateway.enqueue(text("Queued."));

        brain.post(said("mine 3 stone"));

        assertEquals(2, gateway.requests.size(), "a silent acknowledgement is not an answer");
        assertEquals(List.of("Queued."), speaker.said);
    }

    @Test
    void filteredTextWithASuccessfulPlanStillGetsAReply() {
        List<String> unanswerable =
                List.of("[silent]", "<tool_calls><invoke name=\"queue_add\"></invoke></tool_calls>", "task #42");

        for (String text : unanswerable) {
            setUp();
            gateway.enqueue(calls(text, call("q1", "queue_add")));
            gateway.enqueue(text("Queued it."));

            brain.post(said("mine 3 stone"));

            assertEquals(2, gateway.requests.size(), "a reply the guard removes is not an answer: " + text);
            assertEquals(List.of("Queued it."), speaker.said, text);
        }
    }

    @Test
    void aRepeatedEventReplyStillGetsAnAnswer() {
        gateway.enqueue(calls("All done.", call("q1", "queue_add")));
        gateway.enqueue(calls("All done.", call("q2", "queue_add")));
        gateway.enqueue(text("Fresh answer."));

        brain.post(new BrainEvent.TaskFinished(1, "mine_blocks", true, "done"));
        brain.post(new BrainEvent.TaskFinished(2, "mine_blocks", true, "done"));

        assertEquals(3, gateway.requests.size(), "a restated line is not an answer either");
        assertEquals(List.of("All done.", "Fresh answer."), speaker.said);
    }

    @Test
    void aFailedPlanKeepsTheTurnOpenForOneRepair() {
        tools.results.put(
                "queue_add",
                new ArrayDeque<>(List.of(
                        ToolResult.error("Invalid plan: step 1: unknown tool 'teleport'"),
                        ToolResult.ok("plan #7 added: 1 jobs"))));
        gateway.enqueue(calls("On it.", call("q1", "queue_add")));
        gateway.enqueue(calls("Corrected and queued.", call("q2", "queue_add")));

        brain.post(said("mine 3 stone"));

        assertEquals(2, gateway.requests.size(), "the model must see the plan error");
        assertEquals(List.of("On it.", "Corrected and queued."), speaker.said);
        assertEquals(List.of("queue_add", "queue_add"), tools.executed);
        assertFalse(brain.isThinking());
        assertTrue(
                gateway.requests.get(1).messages().stream()
                        .anyMatch(message -> message.role() == Role.TOOL
                                && "q1".equals(message.toolCallId())
                                && message.text().contains("unknown tool 'teleport'")),
                "the repair request carries the error as the tool result of its call: "
                        + gateway.requests.get(1).messages());
    }

    @Test
    void anotherFailedCallKeepsTheTurnOpenForRepair() {
        tools.results.put("stay", new ArrayDeque<>(List.of(ToolResult.error("cannot stay here"))));
        gateway.enqueue(calls("On it.", call("q1", "queue_add"), call("s1", "stay")));
        gateway.enqueue(text("Fixed."));

        brain.post(said("mine 3 stone"));

        assertEquals(2, gateway.requests.size(), "a failed call is not hidden by a queued plan");
        assertEquals(List.of("On it.", "Fixed."), speaker.said);
    }

    @Test
    void queueMutationKeepsTheNormalFollowUp() {
        gateway.enqueue(calls("On it.", call("q1", "queue_add"), call("e1", "queue_edit")));
        gateway.enqueue(text("Cleared."));

        brain.post(said("mine 3 stone"));

        assertEquals(2, gateway.requests.size(), "an edit after the add changes the plan the reply described");
        assertEquals(List.of("On it.", "Cleared."), speaker.said);
    }

    @Test
    void planPatchKeepsTheNormalFollowUp() {
        gateway.enqueue(calls("Patched.", call("q1", "queue_add"), call("p1", "plan_patch")));
        gateway.enqueue(text("Verified."));

        brain.post(said("mine 3 stone"));

        assertEquals(2, gateway.requests.size());
        assertEquals(List.of("Patched.", "Verified."), speaker.said);
    }

    @Test
    void aOneShotTurnKeepsItsCallsAndResultsInHistory() {
        gateway.enqueue(calls("On it.", call("q1", "queue_add")));
        gateway.enqueue(text("Anything else?"));

        brain.post(said("mine 3 stone"));
        brain.post(said("thanks"));

        List<Message> history = gateway.requests.get(1).messages();
        int assistantAt = -1;
        for (int i = 0; i < history.size(); i++) {
            Message message = history.get(i);
            if (message.role() == Role.ASSISTANT
                    && message.toolCalls().stream().anyMatch(call -> "q1".equals(call.id()))) {
                assistantAt = i;
            }
        }
        assertTrue(assistantAt >= 0, "the assistant call is retained: " + history);
        Message result = history.get(assistantAt + 1);
        assertEquals(Role.TOOL, result.role(), "its result follows the call: " + history);
        assertEquals("q1", result.toolCallId());
        assertEquals("queue_add", result.toolName());
    }
}
