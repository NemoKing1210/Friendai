package dev.friedai.llm.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friedai.llm.http.HttpTransport;
import dev.friedai.llm.model.LlmRequest;
import dev.friedai.llm.model.LlmResponse;
import dev.friedai.llm.model.Message;
import dev.friedai.llm.model.StopReason;
import dev.friedai.llm.model.ToolCall;
import dev.friedai.llm.provider.AuthStyle;
import dev.friedai.llm.provider.Endpoint;
import dev.friedai.testutil.MockHttpServer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OllamaProtocolTest {
    private final OllamaProtocol protocol = new OllamaProtocol(
            new HttpTransport(HttpClient.newHttpClient(), Duration.ofSeconds(5), 0, Duration.ofMillis(1)));

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void toWire() {
        ToolCall call = new ToolCall("c", "go_to", json("{\"x\":1}"), "{\"x\":1}", null);
        JsonObject w = protocol.toWire(new LlmRequest(
                "llama", "SYS",
                List.of(Message.user("go"), Message.assistant("", List.of(call)), Message.toolResult("c", "go_to", "ok")),
                List.of(), null, 1024));
        assertFalse(w.get("stream").getAsBoolean());
        assertEquals("system", w.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        assertEquals(1024, w.getAsJsonObject("options").get("num_predict").getAsInt());
        JsonObject fn = w.getAsJsonArray("messages").get(2).getAsJsonObject()
                .getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function");
        assertTrue(fn.get("arguments").isJsonObject());
        assertEquals(1, fn.getAsJsonObject("arguments").get("x").getAsInt());
        JsonObject tool = w.getAsJsonArray("messages").get(3).getAsJsonObject();
        assertEquals("tool", tool.get("role").getAsString());
        assertEquals("go_to", tool.get("tool_name").getAsString());
        assertEquals("ok", tool.get("content").getAsString());
    }

    @Test
    void fromWireToolCallsWithoutIds() {
        LlmResponse r = protocol.fromWire(json("""
                {"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"stay","arguments":{}}}]},
                 "done_reason":"stop","prompt_eval_count":5,"eval_count":6}"""));
        assertEquals("call_0", r.toolCalls().get(0).id());
        assertEquals(StopReason.TOOL_USE, r.stopReason());
        assertEquals(5, r.usage().inputTokens());
        assertEquals(6, r.usage().outputTokens());
        assertEquals(StopReason.MAX_TOKENS, protocol.fromWire(json("""
                {"message":{"role":"assistant","content":"cut"},"done_reason":"length"}""")).stopReason());
    }

    @Test
    void completeAndListModelsHitNativePaths() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.enqueue(200, "{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"done_reason\":\"stop\"}");
            server.enqueue(200, "{\"models\":[{\"name\":\"llama3\"}]}");
            Endpoint endpoint = new Endpoint(server.baseUrl(), "", AuthStyle.NONE);
            protocol.complete(endpoint, new LlmRequest("llama", "SYS", List.of(Message.user("hi")), List.of(), null, 64)).get();
            assertEquals(List.of("llama3"), protocol.listModels(endpoint).get());
            assertEquals("/api/chat", server.requests().get(0).path());
            assertEquals("/api/tags", server.requests().get(1).path());
        }
    }
}
