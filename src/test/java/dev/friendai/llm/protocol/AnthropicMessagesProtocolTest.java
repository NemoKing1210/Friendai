package dev.friendai.llm.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.llm.http.HttpTransport;
import dev.friendai.llm.model.LlmRequest;
import dev.friendai.llm.model.LlmResponse;
import dev.friendai.llm.model.Message;
import dev.friendai.llm.model.StopReason;
import dev.friendai.llm.model.ToolCall;
import dev.friendai.llm.model.ToolSpec;
import dev.friendai.llm.provider.AuthStyle;
import dev.friendai.llm.provider.Endpoint;
import dev.friendai.testutil.MockHttpServer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnthropicMessagesProtocolTest {
    private final AnthropicMessagesProtocol protocol = new AnthropicMessagesProtocol(
            new HttpTransport(HttpClient.newHttpClient(), Duration.ofSeconds(5), 0, Duration.ofMillis(1)));

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static LlmRequest request(List<Message> messages, List<ToolSpec> tools) {
        return new LlmRequest("claude-x", "SYS", messages, tools, null, 1024);
    }

    @Test
    void toWireSystemTopLevelAndMaxTokensRequired() {
        JsonObject schema = json("{\"type\":\"object\",\"properties\":{}}");
        JsonObject w = protocol.toWire(
                request(List.of(Message.user("hi")), List.of(new ToolSpec("stay", "Stay put", schema))));
        assertEquals("SYS", w.get("system").getAsString());
        assertEquals(1024, w.get("max_tokens").getAsInt());
        JsonObject t = w.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("stay", t.get("name").getAsString());
        assertEquals("Stay put", t.get("description").getAsString());
        assertEquals(schema, t.getAsJsonObject("input_schema"));
    }

    @Test
    void toWireAssistantBlocks() {
        ToolCall ok = new ToolCall("a", "go_to", json("{\"x\":1}"), "{\"x\":1}", null);
        ToolCall malformed = new ToolCall("b", "stay", null, "{bad", null);
        JsonObject w = protocol.toWire(request(
                List.of(Message.user("go"), Message.assistant("On it", List.of(ok, malformed))), List.of()));
        JsonArray content =
                w.getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("content");
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
        JsonObject use = content.get(1).getAsJsonObject();
        assertEquals("tool_use", use.get("type").getAsString());
        assertEquals("a", use.get("id").getAsString());
        assertEquals(1, use.getAsJsonObject("input").get("x").getAsInt());
        assertEquals(new JsonObject(), content.get(2).getAsJsonObject().getAsJsonObject("input"));
    }

    @Test
    void mergesConsecutiveToolResultsIntoOneUserMessage() {
        ToolCall a = new ToolCall("a", "x", new JsonObject(), "{}", null);
        ToolCall b = new ToolCall("b", "y", new JsonObject(), "{}", null);
        JsonObject w = protocol.toWire(request(
                List.of(
                        Message.user("go"), Message.assistant("", List.of(a, b)),
                        Message.toolResult("a", "x", "1"), Message.toolResult("b", "y", "2")),
                List.of()));
        JsonArray messages = w.getAsJsonArray("messages");
        assertEquals(3, messages.size());
        JsonArray results = messages.get(2).getAsJsonObject().getAsJsonArray("content");
        assertEquals("user", messages.get(2).getAsJsonObject().get("role").getAsString());
        assertEquals(2, results.size());
        assertEquals("tool_result", results.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("a", results.get(0).getAsJsonObject().get("tool_use_id").getAsString());
        assertEquals("b", results.get(1).getAsJsonObject().get("tool_use_id").getAsString());
    }

    @Test
    void mergesConsecutiveUserMessages() {
        ToolCall a = new ToolCall("a", "x", new JsonObject(), "{}", null);
        JsonObject w = protocol.toWire(request(
                List.of(
                        Message.user("go"), Message.assistant("", List.of(a)),
                        Message.toolResult("a", "x", "1"), Message.user("[event] done")),
                List.of()));
        JsonArray messages = w.getAsJsonArray("messages");
        assertEquals(3, messages.size());
        JsonArray content = messages.get(2).getAsJsonObject().getAsJsonArray("content");
        assertEquals("tool_result", content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("text", content.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals(
                "[event] done", content.get(1).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void fromWireTextAndToolUse() {
        LlmResponse r = protocol.fromWire(json("""
                {"stop_reason":"tool_use","content":[{"type":"text","text":"Mining"},
                 {"type":"tool_use","id":"t1","name":"mine_blocks","input":{"count":10}}],
                 "usage":{"input_tokens":7,"output_tokens":8}}"""));
        assertEquals("Mining", r.text());
        assertEquals(StopReason.TOOL_USE, r.stopReason());
        assertEquals(10, r.toolCalls().get(0).arguments().get("count").getAsInt());
        assertEquals(
                StopReason.END,
                protocol.fromWire(json("{\"stop_reason\":\"end_turn\",\"content\":[]}"))
                        .stopReason());
        assertEquals(
                StopReason.MAX_TOKENS,
                protocol.fromWire(json("{\"stop_reason\":\"max_tokens\",\"content\":[]}"))
                        .stopReason());
    }

    @Test
    void sendsAnthropicVersionHeader() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.enqueue(200, "{\"stop_reason\":\"end_turn\",\"content\":[]}");
            Endpoint endpoint = new Endpoint(server.baseUrl(), "k", AuthStyle.X_API_KEY);
            protocol.complete(endpoint, request(List.of(Message.user("hi")), List.of()))
                    .get();
            MockHttpServer.RecordedRequest r = server.requests().get(0);
            assertEquals("/messages", r.path());
            assertEquals("2023-06-01", r.header("anthropic-version"));
            assertEquals("k", r.header("x-api-key"));
        }
    }
}
