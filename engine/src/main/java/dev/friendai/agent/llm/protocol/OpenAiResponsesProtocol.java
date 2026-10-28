package dev.friendai.agent.llm.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.agent.llm.JsonHttpTransport;
import dev.friendai.agent.llm.model.LlmRequest;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.llm.provider.Endpoint;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** OpenAI Responses API wire format. */
public final class OpenAiResponsesProtocol implements LlmProtocol {
    private final JsonHttpTransport transport;

    public OpenAiResponsesProtocol(JsonHttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public String id() {
        return "openai_responses";
    }

    @Override
    public CompletableFuture<LlmResponse> complete(Endpoint endpoint, LlmRequest request) {
        return transport
                .postJson(
                        ProtocolSupport.uri(endpoint, "/responses"), ProtocolSupport.headers(endpoint), toWire(request))
                .thenApply(this::fromWire);
    }

    @Override
    public CompletableFuture<List<String>> listModels(Endpoint endpoint) {
        return transport
                .getJson(ProtocolSupport.uri(endpoint, "/models"), ProtocolSupport.headers(endpoint))
                .thenApply(o -> ProtocolSupport.idsOf(ProtocolSupport.arr(o, "data"), "id"));
    }

    public JsonObject toWire(LlmRequest r) {
        JsonObject body = new JsonObject();
        body.addProperty("model", r.model());
        body.addProperty("instructions", r.system());
        body.addProperty("store", false);
        body.addProperty("max_output_tokens", r.maxTokens());
        if (r.jsonObject()) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            JsonObject text = new JsonObject();
            text.add("format", format);
            body.add("text", text);
        }
        if (r.temperature() != null) {
            body.addProperty("temperature", r.temperature());
        }
        JsonArray input = new JsonArray();
        for (Message m : r.messages()) {
            switch (m.role()) {
                case USER -> input.add(roleItem("user", m.text()));
                case ASSISTANT -> {
                    if (!m.text().isEmpty()) {
                        input.add(roleItem("assistant", m.text()));
                    }
                    for (ToolCall c : m.toolCalls()) {
                        JsonObject item = new JsonObject();
                        item.addProperty("type", "function_call");
                        item.addProperty("call_id", c.id());
                        item.addProperty("name", c.name());
                        item.addProperty("arguments", ProtocolSupport.argumentsString(c.arguments()));
                        input.add(item);
                    }
                }
                case TOOL -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("type", "function_call_output");
                    item.addProperty("call_id", m.toolCallId());
                    item.addProperty("output", m.text());
                    input.add(item);
                }
            }
        }
        body.add("input", input);
        if (r.tools() != null && !r.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSpec t : r.tools()) {
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.addProperty("name", t.name());
                tool.addProperty("description", t.description());
                tool.add("parameters", t.parametersSchema());
                tools.add(tool);
            }
            body.add("tools", tools);
        }
        return body;
    }

    private static JsonObject roleItem(String role, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", text);
        return o;
    }

    public LlmResponse fromWire(JsonObject o) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        int index = 0;
        for (JsonElement e : ProtocolSupport.arr(o, "output")) {
            JsonObject item = e.getAsJsonObject();
            String type = ProtocolSupport.str(item, "type");
            if ("message".equals(type)) {
                for (JsonElement part : ProtocolSupport.arr(item, "content")) {
                    JsonObject p = part.getAsJsonObject();
                    if ("output_text".equals(ProtocolSupport.str(p, "type"))) {
                        text.append(ProtocolSupport.str(p, "text"));
                    }
                }
            } else if ("function_call".equals(type)) {
                String raw = ProtocolSupport.str(item, "arguments");
                String id = ProtocolSupport.str(item, "call_id");
                if (id == null) {
                    id = ProtocolSupport.str(item, "id");
                }
                calls.add(new ToolCall(
                        id != null ? id : ProtocolSupport.callId(index),
                        ProtocolSupport.str(item, "name"),
                        ProtocolSupport.parseArguments(raw),
                        raw,
                        null));
                index++;
            }
        }
        StopReason stop;
        JsonObject incomplete = ProtocolSupport.obj(o, "incomplete_details");
        if ("incomplete".equals(ProtocolSupport.str(o, "status"))
                && incomplete != null
                && "max_output_tokens".equals(ProtocolSupport.str(incomplete, "reason"))) {
            stop = StopReason.MAX_TOKENS;
        } else if (!calls.isEmpty()) {
            stop = StopReason.TOOL_USE;
        } else {
            stop = StopReason.END;
        }
        JsonObject usage = ProtocolSupport.obj(o, "usage");
        return new LlmResponse(
                text.toString(),
                calls,
                stop,
                new Usage(
                        ProtocolSupport.intOr(usage, "input_tokens", 0),
                        ProtocolSupport.intOr(usage, "output_tokens", 0)));
    }
}
