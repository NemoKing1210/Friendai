package dev.friendai.agent.llm.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.llm.http.HttpTransport;
import dev.friendai.agent.llm.model.LlmRequest;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiResponsesProtocolTest {
    private final OpenAiResponsesProtocol protocol = new OpenAiResponsesProtocol(
            new HttpTransport(HttpClient.newHttpClient(), Duration.ofSeconds(5), 0, Duration.ofMillis(1)));

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static LlmRequest request(List<Message> messages, List<ToolSpec> tools) {
        return new LlmRequest("gpt-x", "SYS", messages, tools, null, 1024);
    }

    @Test
    void toWireUsesInstructionsAndInput() {
        JsonObject w = protocol.toWire(request(List.of(Message.user("hi")), List.of()));
        assertEquals("SYS", w.get("instructions").getAsString());
        assertFalse(w.get("store").getAsBoolean());
        assertEquals(1024, w.get("max_output_tokens").getAsInt());
        JsonObject item = w.getAsJsonArray("input").get(0).getAsJsonObject();
        assertEquals("user", item.get("role").getAsString());
        assertEquals("hi", item.get("content").getAsString());
        assertFalse(w.has("temperature"));
        assertFalse(w.has("text"));
    }

    @Test
    void toWireRequestsJsonObjectOnlyWhenEnabled() {
        LlmRequest request = new LlmRequest("gpt-x", "SYS", List.of(), List.of(), null, 1024, true);
        assertEquals(
                "json_object",
                protocol.toWire(request)
                        .getAsJsonObject("text")
                        .getAsJsonObject("format")
                        .get("type")
                        .getAsString());
    }

    @Test
    void toWireMapsToolCallsAndOutputs() {
        ToolCall call = new ToolCall("c1", "stay", json("{}"), "{}", null);
        JsonObject w = protocol.toWire(request(
                List.of(
                        Message.user("go"),
                        Message.assistant("On it", List.of(call)),
                        Message.toolResult("c1", "stay", "ok")),
                List.of()));
        var input = w.getAsJsonArray("input");
        assertEquals("assistant", input.get(1).getAsJsonObject().get("role").getAsString());
        JsonObject fc = input.get(2).getAsJsonObject();
        assertEquals("function_call", fc.get("type").getAsString());
        assertEquals("c1", fc.get("call_id").getAsString());
        assertEquals("stay", fc.get("name").getAsString());
        assertEquals("{}", fc.get("arguments").getAsString());
        JsonObject out = input.get(3).getAsJsonObject();
        assertEquals("function_call_output", out.get("type").getAsString());
        assertEquals("c1", out.get("call_id").getAsString());
        assertEquals("ok", out.get("output").getAsString());
    }

    @Test
    void toWireToolsAreFlat() {
        JsonObject schema = json("{\"type\":\"object\",\"properties\":{}}");
        JsonObject w = protocol.toWire(request(List.of(), List.of(new ToolSpec("stay", "Stay put", schema))));
        JsonObject t = w.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("function", t.get("type").getAsString());
        assertEquals("stay", t.get("name").getAsString());
        assertEquals("Stay put", t.get("description").getAsString());
        assertEquals(schema, t.getAsJsonObject("parameters"));
    }

    @Test
    void fromWireMessageAndFunctionCall() {
        LlmResponse r = protocol.fromWire(json("""
                {"status":"completed","output":[
                  {"type":"message","content":[{"type":"output_text","text":"On my way"}]},
                  {"type":"function_call","call_id":"c1","name":"go_to","arguments":"{\\"x\\":1}"}],
                 "usage":{"input_tokens":3,"output_tokens":4}}"""));
        assertEquals("On my way", r.text());
        assertEquals(1, r.toolCalls().size());
        assertEquals("c1", r.toolCalls().get(0).id());
        assertEquals(1, r.toolCalls().get(0).arguments().get("x").getAsInt());
        assertEquals(StopReason.TOOL_USE, r.stopReason());
        assertEquals(3, r.usage().inputTokens());
    }

    @Test
    void fromWireIncompleteMaxTokens() {
        LlmResponse r = protocol.fromWire(json("""
                {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}"""));
        assertEquals(StopReason.MAX_TOKENS, r.stopReason());
    }
}
