package dev.friendai.llm.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

class GeminiProtocolTest {
    private final GeminiProtocol protocol = new GeminiProtocol(
            new HttpTransport(HttpClient.newHttpClient(), Duration.ofSeconds(5), 0, Duration.ofMillis(1)));

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static LlmRequest request(List<Message> messages, List<ToolSpec> tools) {
        return new LlmRequest("gemini-x", "SYS", messages, tools, null, 1024);
    }

    @Test
    void toWireStructure() {
        JsonObject schema = json("{\"type\":\"object\",\"properties\":{}}");
        ToolCall call = new ToolCall("c", "stay", json("{}"), "{}", null);
        JsonObject w = protocol.toWire(request(
                List.of(Message.user("hi"), Message.assistant("ok", List.of(call))),
                List.of(new ToolSpec("stay", "Stay put", schema))));
        assertEquals(
                "SYS",
                w.getAsJsonObject("systemInstruction")
                        .getAsJsonArray("parts")
                        .get(0)
                        .getAsJsonObject()
                        .get("text")
                        .getAsString());
        assertEquals(
                "stay",
                w.getAsJsonArray("tools")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("functionDeclarations")
                        .get(0)
                        .getAsJsonObject()
                        .get("name")
                        .getAsString());
        assertEquals(
                1024,
                w.getAsJsonObject("generationConfig").get("maxOutputTokens").getAsInt());
        assertFalse(w.getAsJsonObject("generationConfig").has("temperature"));
        assertFalse(w.getAsJsonObject("generationConfig").has("responseMimeType"));
        JsonArray contents = w.getAsJsonArray("contents");
        assertEquals("user", contents.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals(
                "hi",
                contents.get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("parts")
                        .get(0)
                        .getAsJsonObject()
                        .get("text")
                        .getAsString());
        assertEquals("model", contents.get(1).getAsJsonObject().get("role").getAsString());
    }

    @Test
    void toWireRequestsJsonMimeTypeOnlyWhenEnabled() {
        LlmRequest request = new LlmRequest("gemini-x", "SYS", List.of(), List.of(), null, 1024, true);
        assertEquals(
                "application/json",
                protocol.toWire(request)
                        .getAsJsonObject("generationConfig")
                        .get("responseMimeType")
                        .getAsString());
    }

    @Test
    void mergesFunctionResponses() {
        ToolCall a = new ToolCall("a", "x", new JsonObject(), "{}", null);
        ToolCall b = new ToolCall("b", "y", new JsonObject(), "{}", null);
        JsonObject w = protocol.toWire(request(
                List.of(
                        Message.user("go"), Message.assistant("", List.of(a, b)),
                        Message.toolResult("a", "x", "1"), Message.toolResult("b", "y", "2")),
                List.of()));
        JsonArray contents = w.getAsJsonArray("contents");
        assertEquals(3, contents.size());
        JsonArray parts = contents.get(2).getAsJsonObject().getAsJsonArray("parts");
        assertEquals(2, parts.size());
        JsonObject fr = parts.get(0).getAsJsonObject().getAsJsonObject("functionResponse");
        assertEquals("x", fr.get("name").getAsString());
        assertEquals("1", fr.getAsJsonObject("response").get("content").getAsString());
        assertEquals(
                "y",
                parts.get(1)
                        .getAsJsonObject()
                        .getAsJsonObject("functionResponse")
                        .get("name")
                        .getAsString());
    }

    @Test
    void roundTripsThoughtSignature() {
        LlmResponse r = protocol.fromWire(json("""
                {"candidates":[{"content":{"parts":[
                  {"functionCall":{"name":"stay","args":{}},"thoughtSignature":"sig1"}]},"finishReason":"STOP"}]}"""));
        ToolCall call = r.toolCalls().get(0);
        assertEquals("sig1", call.signature());
        JsonObject w =
                protocol.toWire(request(List.of(Message.user("go"), Message.assistant("", List.of(call))), List.of()));
        JsonObject part = w.getAsJsonArray("contents")
                .get(1)
                .getAsJsonObject()
                .getAsJsonArray("parts")
                .get(0)
                .getAsJsonObject();
        assertEquals("sig1", part.get("thoughtSignature").getAsString());
    }

    @Test
    void generatesIdsWhenMissing() {
        LlmResponse r = protocol.fromWire(json("""
                {"candidates":[{"content":{"parts":[
                  {"functionCall":{"name":"a","args":{}}},{"functionCall":{"name":"b","args":{}}},
                  {"functionCall":{"id":"given","name":"c","args":{}}}]},"finishReason":"STOP"}]}"""));
        assertEquals("call_0", r.toolCalls().get(0).id());
        assertEquals("call_1", r.toolCalls().get(1).id());
        assertEquals("given", r.toolCalls().get(2).id());
    }

    @Test
    void finishReasonMapping() {
        assertEquals(StopReason.TOOL_USE, protocol.fromWire(json("""
                {"candidates":[{"content":{"parts":[{"functionCall":{"name":"a","args":{}}}]},"finishReason":"STOP"}]}""")).stopReason());
        assertEquals(StopReason.END, protocol.fromWire(json("""
                {"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"STOP"}]}""")).stopReason());
        assertEquals(StopReason.MAX_TOKENS, protocol.fromWire(json("""
                {"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"MAX_TOKENS"}]}""")).stopReason());
    }

    @Test
    void usesGoogApiKeyHeader() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.enqueue(
                    200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]},\"finishReason\":\"STOP\"}]}");
            Endpoint endpoint = new Endpoint(server.baseUrl(), "secret", AuthStyle.GOOG_API_KEY);
            protocol.complete(endpoint, request(List.of(Message.user("hi")), List.of()))
                    .get();
            MockHttpServer.RecordedRequest r = server.requests().get(0);
            assertEquals("/models/gemini-x:generateContent", r.path());
            assertEquals("secret", r.header("x-goog-api-key"));
        }
    }

    @Test
    void listModelsStripsPrefix() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.enqueue(200, "{\"models\":[{\"name\":\"models/gemini-a\"},{\"name\":\"models/gemini-b\"}]}");
            Endpoint endpoint = new Endpoint(server.baseUrl(), "k", AuthStyle.GOOG_API_KEY);
            assertEquals(
                    List.of("gemini-a", "gemini-b"),
                    protocol.listModels(endpoint).get());
        }
    }
}
