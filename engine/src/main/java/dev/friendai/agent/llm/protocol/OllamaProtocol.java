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

/** Native Ollama chat wire format. */
public final class OllamaProtocol implements LlmProtocol {
    private final JsonHttpTransport transport;

    public OllamaProtocol(JsonHttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public String id() {
        return "ollama";
    }

    @Override
    public CompletableFuture<LlmResponse> complete(Endpoint endpoint, LlmRequest request) {
        return transport
                .postJson(
                        ProtocolSupport.uri(endpoint, "/api/chat"), ProtocolSupport.headers(endpoint), toWire(request))
                .thenApply(this::fromWire);
    }

    @Override
    public CompletableFuture<List<String>> listModels(Endpoint endpoint) {
        return transport
                .getJson(ProtocolSupport.uri(endpoint, "/api/tags"), ProtocolSupport.headers(endpoint))
                .thenApply(o -> ProtocolSupport.idsOf(ProtocolSupport.arr(o, "models"), "name"));
    }

    public JsonObject toWire(LlmRequest r) {
        JsonObject body = new JsonObject();
        body.addProperty("model", r.model());
        body.addProperty("stream", false);
        JsonArray messages = new JsonArray();
        messages.add(message("system", r.system()));
        for (Message m : r.messages()) {
            switch (m.role()) {
                case USER -> messages.add(message("user", m.text()));
                case TOOL -> {
                    JsonObject o = message("tool", m.text());
                    o.addProperty("tool_name", m.toolName());
                    messages.add(o);
                }
                case ASSISTANT -> {
                    JsonObject o = message("assistant", m.text());
                    if (!m.toolCalls().isEmpty()) {
                        JsonArray calls = new JsonArray();
                        for (ToolCall c : m.toolCalls()) {
                            JsonObject fn = new JsonObject();
                            fn.addProperty("name", c.name());
                            fn.add("arguments", c.arguments() == null ? new JsonObject() : c.arguments());
                            JsonObject call = new JsonObject();
                            call.add("function", fn);
                            calls.add(call);
                        }
                        o.add("tool_calls", calls);
                    }
                    messages.add(o);
                }
            }
        }
        body.add("messages", messages);
        if (r.jsonObject()) {
            body.addProperty("format", "json");
        }
        if (r.tools() != null && !r.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSpec t : r.tools()) {
                JsonObject fn = new JsonObject();
                fn.addProperty("name", t.name());
                fn.addProperty("description", t.description());
                fn.add("parameters", t.parametersSchema());
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.add("function", fn);
                tools.add(tool);
            }
            body.add("tools", tools);
        }
        JsonObject options = new JsonObject();
        options.addProperty("num_predict", r.maxTokens());
        if (r.temperature() != null) {
            options.addProperty("temperature", r.temperature());
        }
        body.add("options", options);
        return body;
    }

    private static JsonObject message(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    public LlmResponse fromWire(JsonObject o) {
        JsonObject message = ProtocolSupport.obj(o, "message");
        String text = "";
        List<ToolCall> calls = new ArrayList<>();
        if (message != null) {
            String content = ProtocolSupport.str(message, "content");
            text = content == null ? "" : content;
            int index = 0;
            for (JsonElement e : ProtocolSupport.arr(message, "tool_calls")) {
                JsonObject fn = ProtocolSupport.obj(e.getAsJsonObject(), "function");
                if (fn == null) {
                    continue;
                }
                JsonObject args = ProtocolSupport.obj(fn, "arguments");
                JsonObject safeArgs = args == null ? new JsonObject() : args;
                calls.add(new ToolCall(
                        ProtocolSupport.callId(index),
                        ProtocolSupport.str(fn, "name"),
                        safeArgs,
                        safeArgs.toString(),
                        null));
                index++;
            }
        }
        String done = ProtocolSupport.str(o, "done_reason");
        StopReason stop;
        if ("length".equals(done)) {
            stop = StopReason.MAX_TOKENS;
        } else if (!calls.isEmpty()) {
            stop = StopReason.TOOL_USE;
        } else {
            stop = StopReason.END;
        }
        return new LlmResponse(
                text,
                calls,
                stop,
                new Usage(ProtocolSupport.intOr(o, "prompt_eval_count", 0), ProtocolSupport.intOr(o, "eval_count", 0)));
    }
}
