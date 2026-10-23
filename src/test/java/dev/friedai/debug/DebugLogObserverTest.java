package dev.friedai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import dev.friedai.brain.BrainEvent;
import dev.friedai.llm.model.ToolCall;
import dev.friedai.tool.ToolResult;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class DebugLogObserverTest {
    @Test
    void mapsEachCallbackToExpectedKind() {
        DebugLog log = new DebugLog(50, 500, UnaryOperator.identity(), () -> 0L);
        DebugLogObserver observer = new DebugLogObserver(log);
        ToolCall call = new ToolCall("c1", "go_to", new JsonObject(), "{\"x\":1}", null);

        observer.onEvent(new BrainEvent.PlayerSaid(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Steve", "hi", BrainEvent.Source.CHAT));
        observer.onEvent(new BrainEvent.TaskFinished(1, "stay", true, "ok"));
        observer.onAssistantText("Hello");
        observer.onToolCall(call);
        observer.onToolResult(call, ToolResult.ok("went"));
        observer.onToolResult(call, ToolResult.error("blocked"));
        observer.onError(new IllegalStateException("boom"));

        List<LogEntry> entries = log.after(0, 50);
        assertEquals(
                List.of(
                        LogKind.USER,
                        LogKind.TASK,
                        LogKind.ASSISTANT,
                        LogKind.TOOL_CALL,
                        LogKind.TOOL_RESULT,
                        LogKind.TOOL_RESULT,
                        LogKind.ERROR),
                entries.stream().map(LogEntry::kind).toList());
        assertEquals("[Steve]: hi", entries.get(0).text());
        assertEquals("go_to {\"x\":1}", entries.get(3).text());
        assertEquals("go_to: went", entries.get(4).text());
        assertEquals("go_to: ERROR blocked", entries.get(5).text());
        assertEquals("java.lang.IllegalStateException: boom", entries.get(6).text());
    }
}
