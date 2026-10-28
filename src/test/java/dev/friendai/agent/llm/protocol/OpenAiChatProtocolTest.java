package dev.friendai.agent.llm.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.llm.http.HttpTransport;
import dev.friendai.agent.llm.model.LlmRequest;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.llm.provider.AuthStyle;
import dev.friendai.agent.llm.provider.Endpoint;
import dev.friendai.testutil.MockHttpServer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiChatProtocolTest {
    private static final String TEXT_RESPONSE = """
            {"choices":[{"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":12,"completion_tokens":5}}""";
    private static final String TOOL_RESPONSE = """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"c1","type":"function","function":{"name":"mine_blocks","arguments":"{\\"count\\": 10}"}},
              {"id":"c2","type":"function","function":{"name":"stay","arguments":"{}"}}]},
              "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}""";
    private static final String MALFORMED_RESPONSE = """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"c1","type":"function","function":{"name":"mine_blocks","arguments":"{\\"count\\": 10"}}]},
              "finish_reason":"tool_calls"}]}""";

    private final OpenAiChatProtocol protocol = new OpenAiChatProtocol(
            new HttpTransport(HttpClient.newHttpClient(), Duration.ofSeconds(5), 0, Duration.ofMillis(1)));

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static JsonObject schema() {
        return json("{\"type\":\"object\",\"properties\":{}}");
    }

    private static LlmRequest request(List<Message> messages, List<ToolSpec> tools, Double temperature) {
        return new LlmRequest("gpt-x", "SYS", messages, tools, temperature, 1024);
    }

    @Test
    void toWirePutsSystemFirst() {
        JsonObject w = protocol.toWire(request(List.of(Message.user("hi")), List.of(), null));
        assertEquals(
                "system",
                w.getAsJsonArray("messages")
                        .get(0)
                        .getAsJsonObject()
                        .get("role")
                        .getAsString());
        assertEquals(
                "SYS",
                w.getAsJsonArray("messages")
                        .get(0)
                        .getAsJsonObject()
                        .get("content")
                        .getAsString());
        assertEquals(
                "user",
                w.getAsJsonArray("messages")
                        .get(1)
                        .getAsJsonObject()
                        .get("role")
                        .getAsString());
    }

    @Test
    void toWireMapsTools() {
        JsonObject w = protocol.toWire(request(List.of(), List.of(new ToolSpec("stay", "Stay put", schema())), null));
        JsonObject t = w.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("function", t.get("type").getAsString());
        JsonObject fn = t.getAsJsonObject("function");
        assertEquals("stay", fn.get("name").getAsString());
        assertEquals("Stay put", fn.get("description").getAsString());
        assertEquals(schema(), fn.getAsJsonObject("parameters"));
    }

    @Test
    void toWireMapsAssistantToolCallsAndToolResults() {
        ToolCall call = new ToolCall("c1", "mine_blocks", json("{\"count\":3}"), "{\"count\":3}", null);
        JsonObject w = protocol.toWire(request(
                List.of(
                        Message.user("go"),
                        Message.assistant("", List.of(call)),
                        Message.toolResult("c1", "mine_blocks", "ok")),
                List.of(),
                null));
        JsonObject assistant = w.getAsJsonArray("messages").get(2).getAsJsonObject();
        JsonObject fn =
                assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function");
        assertEquals("{\"count\":3}", fn.get("arguments").getAsString());
        JsonObject tool = w.getAsJsonArray("messages").get(3).getAsJsonObject();
        assertEquals("tool", tool.get("role").getAsString());
        assertEquals("c1", tool.get("tool_call_id").getAsString());
        assertEquals("ok", tool.get("content").getAsString());
    }

    @Test
    void toWireSendsMaxTokensAndOmitsNullTemperature() {
        JsonObject w = protocol.toWire(request(List.of(), List.of(), null));
        assertEquals(1024, w.get("max_tokens").getAsInt());
        assertFalse(w.has("temperature"));
        assertFalse(w.has("response_format"));
    }

    @Test
    void toWireRequestsJsonObjectOnlyWhenEnabled() {
        LlmRequest request = new LlmRequest("gpt-x", "SYS", List.of(), List.of(), null, 1024, true);
        JsonObject responseFormat = protocol.toWire(request).getAsJsonObject("response_format");
        assertEquals("json_object", responseFormat.get("type").getAsString());
    }

    @Test
    void fromWireText() {
        LlmResponse r = protocol.fromWire(json(TEXT_RESPONSE));
        assertEquals("Hello!", r.text());
        assertEquals(StopReason.END, r.stopReason());
        assertEquals(new Usage(12, 5), r.usage());
    }

    @Test
    void fromWireToolCalls() {
        LlmResponse r = protocol.fromWire(json(TOOL_RESPONSE));
        assertEquals(2, r.toolCalls().size());
        assertEquals(10, r.toolCalls().get(0).arguments().get("count").getAsInt());
        assertEquals(StopReason.TOOL_USE, r.stopReason());
        assertEquals("", r.text());
    }

    @Test
    void malformedArgumentsKeptRaw() {
        ToolCall c = protocol.fromWire(json(MALFORMED_RESPONSE)).toolCalls().get(0);
        assertNull(c.arguments());
        assertEquals("{\"count\": 10", c.rawArguments());
    }

    @Test
    void completeHitsChatCompletions() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.enqueue(200, TEXT_RESPONSE);
            Endpoint endpoint = new Endpoint(server.baseUrl(), "k", AuthStyle.BEARER);
            protocol.complete(endpoint, request(List.of(Message.user("hi")), List.of(), 0.5))
                    .get();
            MockHttpServer.RecordedRequest r = server.requests().get(0);
            assertEquals("/chat/completions", r.path());
            assertEquals("Bearer k", r.header("Authorization"));
        }
    }

    @Test
    void listModelsReadsDataIds() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.enqueue(200, "{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}]}");
            Endpoint endpoint = new Endpoint(server.baseUrl(), "k", AuthStyle.BEARER);
            assertEquals(List.of("a", "b"), protocol.listModels(endpoint).get());
            assertEquals("/models", server.requests().get(0).path());
        }
    }
}
