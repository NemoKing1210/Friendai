package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.RequestAttempt;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.plan.PlanSubject;
import dev.friendai.agent.route.PlanNeed;
import dev.friendai.agent.route.PlanningDecision;
import dev.friendai.agent.route.RouteDecision;
import dev.friendai.agent.route.TurnMode;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogContext;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogPipeline;
import dev.friendai.foundation.diagnostics.LogRecord;
import dev.friendai.foundation.diagnostics.LogSettings;
import dev.friendai.foundation.diagnostics.LogSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StructuredLogObserverTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COMPANION = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final class Recall implements LogSink {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void accept(LogRecord record) {
            records.add(record);
        }
    }

    private static LogPipeline pipeline(Recall sink, LogLevel level) {
        LogSettings settings = new LogSettings(level, Set.of(), 1_000_000L, 10_000_000L, 7, 20, true);
        return new LogPipeline(new Recall(), () -> settings, text -> text, sink);
    }

    private static ToolCall call(String name) {
        return new ToolCall("call-1", name, null, "{\"count\":3}", null);
    }

    @Test
    void everyToolCallCapturesItsOwnContextAndTheRecordTrace() {
        Recall sink = new Recall();
        DebugLogObserver observer =
                new DebugLogObserver(OWNER, COMPANION, "s1", pipeline(sink, LogLevel.INFO), "trace-1");
        observer.onTurnStart(4);
        ToolCall first = new ToolCall("call-a", "mine_block_at", null, "{}", null);
        ToolCall second = new ToolCall("call-b", "give_items", null, "{}", null);

        observer.onToolCall(first);
        LogContext firstContext = observer.contextForToolCall("call-a");
        observer.onToolCall(second);
        LogContext secondContext = observer.contextForToolCall("call-b");

        assertEquals("mine_block_at", firstContext.toolCall());
        assertEquals("give_items", secondContext.toolCall());
        assertEquals(4L, firstContext.turn());
        assertEquals("trace-1", firstContext.trace());
        assertEquals(firstContext.trace(), secondContext.trace());
        assertEquals(sink.records.get(1).seq(), firstContext.cause(), "a queued task descends from its tool.call");
        assertEquals(sink.records.get(2).seq(), secondContext.cause());
        assertEquals("trace-1", sink.records.get(1).data().get("trace"));
        assertEquals("trace-1", sink.records.get(0).data().get("trace"));
    }

    @Test
    void anOlderCallNeverBorrowsANewerCallsContext() {
        Recall sink = new Recall();
        DebugLogObserver observer =
                new DebugLogObserver(OWNER, COMPANION, "s1", pipeline(sink, LogLevel.INFO), "trace-2");
        observer.onTurnStart(4);
        observer.onToolCall(new ToolCall("call-a", "mine_block_at", null, "{}", null));
        observer.onToolCall(new ToolCall("call-b", "give_items", null, "{}", null));

        LogContext stale = observer.contextForToolCall("call-a");

        assertEquals("", stale.toolCall(), "an unreachable call gets a detached root, not call-b");
        assertNotEquals("give_items", stale.toolCall());
        assertEquals(4L, stale.turn());
        assertEquals("trace-2", stale.trace());
        assertEquals(sink.records.get(0).seq(), stale.cause(), "the root still descends from the turn");
    }

    @Test
    void providerAttemptsAndDiscardsAreBoundedSafeFacts() {
        Recall sink = new Recall();
        DebugLogObserver observer =
                new DebugLogObserver(OWNER, COMPANION, "s1", pipeline(sink, LogLevel.INFO), "trace-1");
        observer.onRequestAttempt(7L, new RequestAttempt(2, RequestAttempt.RETRY, 429, "rate_limit", 41L, 250L, 1024L));
        observer.onRequestAttempt(7L, new RequestAttempt(3, RequestAttempt.FINISH, 200, "", 90L, 0L, 1024L));
        observer.onRequestDiscarded(7L, "stale_generation");

        assertEquals(
                List.of(LogEvents.LLM_RETRY, LogEvents.LLM_ATTEMPT, LogEvents.LLM_DISCARDED),
                sink.records.stream().map(LogRecord::event).toList());
        LogRecord retry = sink.records.get(0);
        assertEquals("7", retry.data().get("request"));
        assertEquals("429", retry.data().get("status"));
        assertEquals("rate_limit", retry.data().get("error"));
        assertEquals("250", retry.data().get("backoffMs"));
        assertEquals("stale_generation", sink.records.get(2).data().get("reason"));
        assertFalse(retry.message().contains("body"), "an attempt fact never carries a body");
    }

    @Test
    void aRequestRecordsItsSizeButNotItsBodiesAtNormal() {
        Recall sink = new Recall();
        DebugLogObserver observer =
                new DebugLogObserver(OWNER, COMPANION, "s1", pipeline(sink, LogLevel.DEBUG), "trace-1");
        observer.onTurnStart(3);
        observer.onRequest(
                "system prompt",
                List.of(dev.friendai.agent.llm.model.Message.user("a long player message")),
                List.of(new dev.friendai.agent.llm.model.ToolSpec("mine_block_at", "dig", null)));

        LogRecord request = sink.records.get(1);
        assertEquals(LogEvents.LLM_REQUEST, request.event());
        assertEquals("13", request.data().get("systemChars"));
        assertEquals("21", request.data().get("historyChars"));
        assertEquals("1", request.data().get("tools"));
        assertEquals("0", request.data().get("schemaChars"));
        assertTrue(request.message().length() <= 400);
    }

    @Test
    void aNormalEventHidesThePlayerText() {
        Recall sink = new Recall();
        DebugLogObserver observer = new DebugLogObserver(OWNER, COMPANION, "s1", pipeline(sink, LogLevel.INFO));
        observer.onEvent(new BrainEvent.PlayerSaid(OWNER, "Alex", "my secret plans", BrainEvent.Source.CHAT));
        LogRecord posted = sink.records.get(0);
        assertFalse(posted.message().contains("my secret plans"));
        assertTrue(posted.message().contains("PlayerSaid"));
    }

    @Test
    void aTurnStartsThenToolsChainThroughCauses() {
        Recall sink = new Recall();
        DebugLogObserver observer = new DebugLogObserver(OWNER, COMPANION, "s1", pipeline(sink, LogLevel.INFO));
        observer.onTurnStart(4);
        observer.onToolCall(call("mine_block_at"));
        observer.onToolResult(call("mine_block_at"), ToolResult.ok("started"));
        observer.onTurnEnd(4, "delivered");

        List<LogRecord> records = sink.records;
        assertEquals(
                List.of(LogEvents.TURN_START, LogEvents.TOOL_CALL, LogEvents.TOOL_RESULT, LogEvents.TURN_END),
                records.stream().map(LogRecord::event).toList());
        assertEquals(4L, records.get(0).turn());
        assertEquals(4L, records.get(1).turn());
        assertEquals(records.get(0).seq(), records.get(1).cause());
        assertEquals(records.get(1).seq(), records.get(2).cause());
        assertEquals(records.get(0).seq(), records.get(3).cause());
        assertEquals("call-1", records.get(1).toolCall());
        assertEquals(LogCategory.TOOL, records.get(1).category());
        assertEquals(LogKind.TOOL_CALL, records.get(1).kind());
        assertEquals(OWNER, records.get(0).owner());
        assertEquals(COMPANION, records.get(0).companion());
        assertEquals("s1", records.get(0).session());
    }

    @Test
    void anErrorCarriesTypeMessageAndABoundedStack() {
        Recall sink = new Recall();
        DebugLogObserver observer = new DebugLogObserver(OWNER, COMPANION, "s1", pipeline(sink, LogLevel.INFO));
        observer.onTurnStart(1);
        observer.onError(new IllegalStateException("boom"));
        LogRecord error = sink.records.stream()
                .filter(record -> record.level() == LogLevel.ERROR)
                .findFirst()
                .orElseThrow();
        assertEquals(LogEvents.LLM_ERROR, error.event());
        assertEquals("java.lang.IllegalStateException", error.error().type());
        assertTrue(error.error().message().contains("boom"));
        assertTrue(error.error().frames() > 0);
    }

    @Test
    void normalOmitsBodiesAndDetailedKeepsThem() {
        Recall normal = new Recall();
        DebugLogObserver inNormal = new DebugLogObserver(OWNER, COMPANION, "s1", pipeline(normal, LogLevel.INFO));
        inNormal.onToolResult(call("mine_block_at"), ToolResult.ok("a long result body"));
        String normalText = normal.records.get(0).message();
        assertFalse(normalText.contains("a long result body"));

        Recall detailed = new Recall();
        DebugLogObserver inDetailed = new DebugLogObserver(OWNER, COMPANION, "s1", pipeline(detailed, LogLevel.DEBUG));
        inDetailed.onToolResult(call("mine_block_at"), ToolResult.ok("a long result body"));
        assertTrue(detailed.records.get(0).message().contains("a long result body"));
    }

    @Test
    void instructionSkillsNeverWriteTheirFields() {
        Recall sink = new Recall();
        DebugLogObserver observer = new DebugLogObserver(OWNER, COMPANION, "s1", pipeline(sink, LogLevel.DEBUG));
        observer.onToolCall(new ToolCall("c", "manage_skill", null, "{\"body\":\"secret text\"}", null));
        observer.onToolResult(new ToolCall("c", "manage_skill", null, "{}", null), ToolResult.ok("stored secret text"));
        for (LogRecord record : sink.records) {
            assertFalse(record.message().contains("secret text"));
        }
    }

    @Test
    void requestAndResponseAreReachableAtDetailed() {
        Recall sink = new Recall();
        DebugLogObserver observer = new DebugLogObserver(OWNER, COMPANION, "s1", pipeline(sink, LogLevel.DEBUG));
        observer.onTurnStart(2);
        observer.onRequest(
                "system",
                List.of(dev.friendai.agent.llm.model.Message.user("hi")),
                List.of(new dev.friendai.agent.llm.model.ToolSpec("mine_block_at", "mine", null)));
        observer.onResponse(
                new LlmResponse("reply", List.of(), dev.friendai.agent.llm.model.StopReason.END, new Usage(1, 2)));
        assertEquals(
                List.of(LogEvents.LLM_REQUEST, LogEvents.LLM_RESPONSE),
                sink.records.stream()
                        .filter(record -> record.event().startsWith("llm."))
                        .map(LogRecord::event)
                        .toList());
        LogRecord response = sink.records.stream()
                .filter(record -> record.event().equals(LogEvents.LLM_RESPONSE))
                .findFirst()
                .orElseThrow();
        assertEquals(LogKind.RESPONSE, response.kind());
    }

    @Test
    void routeAndPlannerDecisionsBecomePlanRecordsWithoutTheOrderText() {
        Recall sink = new Recall();
        DebugLogObserver observer =
                new DebugLogObserver(OWNER, COMPANION, "s1", pipeline(sink, LogLevel.INFO), "trace-1");
        observer.onTurnStart(2);
        observer.onRouteDecision(new RouteDecision(
                TurnMode.GOAL,
                Set.of(ToolGroup.BUILDING),
                new PlanningDecision(PlanNeed.REQUIRED, new PlanSubject.OwnerOrder("build a house"), "several-kinds"),
                "several kinds of work"));
        observer.onPlannerAttempt(
                new PlannerAttempt(new PlanSubject.OwnerOrder("build a house"), 1, "request", "", -1L, 0L));
        observer.onPlannerAttempt(
                new PlannerAttempt(new PlanSubject.OwnerOrder("build a house"), 1, "delivered", "", 42L, 5L));

        List<LogRecord> records = sink.records;
        assertEquals(
                List.of(
                        LogEvents.TURN_START,
                        LogEvents.ROUTE_DECISION,
                        LogEvents.PLAN_REQUEST,
                        LogEvents.PLAN_DELIVERED),
                records.stream().map(LogRecord::event).toList());
        LogRecord route = records.get(1);
        assertEquals(LogKind.PLANNER, route.kind());
        assertEquals(LogCategory.BRAIN, route.category());
        assertEquals("GOAL", route.data().get("mode"));
        assertEquals("REQUIRED", route.data().get("need"));
        assertEquals("owner_order", route.data().get("subject"));
        assertEquals("several-kinds", route.data().get("planningReason"));
        assertEquals("1", route.data().get("groups"));
        LogRecord delivered = records.get(3);
        assertEquals(5L, delivered.plan(), "the queued plan is a structured field, not just text");
        assertEquals(42L, delivered.durationMs());
        assertEquals("1", delivered.data().get("attempt"));
        assertEquals("delivered", delivered.data().get("disposition"));
        assertFalse(records.stream().anyMatch(r -> String.valueOf(r.message()).contains("build a house")));
        assertFalse(records.stream()
                .anyMatch(r -> r.data().values().stream().anyMatch(value -> value.contains("build a house"))));
    }

    @Test
    void aPlannerFailureAndItsFallbackAreDistinctRecords() {
        Recall sink = new Recall();
        DebugLogObserver observer =
                new DebugLogObserver(OWNER, COMPANION, "s1", pipeline(sink, LogLevel.INFO), "trace-1");
        PlanSubject subject = new PlanSubject.ActiveGoal(4, List.of("2 logs missing"));
        observer.onPlannerAttempt(new PlannerAttempt(subject, 1, "failed", "REQUEST_FAILED", 7L, 0L));
        observer.onPlannerAttempt(new PlannerAttempt(subject, 1, "fallback", "transport", 7L, 0L));

        assertEquals(
                List.of(LogEvents.PLAN_FAILED, LogEvents.PLAN_FALLBACK),
                sink.records.stream().map(LogRecord::event).toList());
        assertEquals("REQUEST_FAILED", sink.records.get(0).data().get("failure"));
        assertEquals("transport", sink.records.get(1).data().get("failure"));
        assertEquals("active_goal", sink.records.get(0).data().get("subject"));
        assertEquals("4", sink.records.get(0).data().get("subjectId"));
    }

    @Test
    void aReplyAndAnAssistantTextStayDistinct() {
        Recall sink = new Recall();
        DebugLogObserver observer = new DebugLogObserver(OWNER, COMPANION, "s1", pipeline(sink, LogLevel.INFO));
        observer.onAssistantText("raw");
        observer.onReply(new Caller(OWNER, "Alex", AccessLevel.OWNER), "heard");
        assertEquals(
                List.of(LogEvents.ASSISTANT_TEXT, LogEvents.ASSISTANT_DELIVERED),
                sink.records.stream().map(LogRecord::event).toList());
        assertNotNull(sink.records.get(1).message());
    }
}
