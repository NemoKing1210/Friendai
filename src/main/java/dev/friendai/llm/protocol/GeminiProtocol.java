package dev.friendai.llm.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.llm.http.HttpTransport;
import dev.friendai.llm.model.LlmRequest;
import dev.friendai.llm.model.LlmResponse;
import dev.friendai.llm.model.Message;
import dev.friendai.llm.model.Role;
import dev.friendai.llm.model.StopReason;
import dev.friendai.llm.model.ToolCall;
import dev.friendai.llm.model.ToolSpec;
import dev.friendai.llm.model.Usage;
import dev.friendai.llm.provider.Endpoint;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Google Gemini generateContent wire format. */
public final class GeminiProtocol implements LlmProtocol {
    private final HttpTransport transport;

    public GeminiProtocol(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public String id() {
        return "gemini";
    }

    @Override
    public CompletableFuture<LlmResponse> complete(Endpoint endpoint, LlmRequest request) {
        String path = "/models/" + request.model() + ":generateContent";
        return transport
                .postJson(ProtocolSupport.uri(endpoint, path), ProtocolSupport.headers(endpoint), toWire(request))
                .thenApply(this::fromWire);
    }

    @Override
    public CompletableFuture<List<String>> listModels(Endpoint endpoint) {
        return transport
                .getJson(ProtocolSupport.uri(endpoint, "/models"), ProtocolSupport.headers(endpoint))
                .thenApply(o -> {
                    List<String> names = new ArrayList<>();
                    for (String name : ProtocolSupport.idsOf(ProtocolSupport.arr(o, "models"), "name")) {
                        names.add(name.startsWith("models/") ? name.substring(7) : name);
                    }
                    return names;
                });
    }

    public JsonObject toWire(LlmRequest r) {
        JsonObject body = new JsonObject();
        JsonObject system = new JsonObject();
        JsonArray systemParts = new JsonArray();
        systemParts.add(textPart(r.system()));
        system.add("parts", systemParts);
        body.add("systemInstruction", system);

        if (r.tools() != null && !r.tools().isEmpty()) {
            JsonArray declarations = new JsonArray();
            for (ToolSpec t : r.tools()) {
                JsonObject d = new JsonObject();
                d.addProperty("name", t.name());
                d.addProperty("description", t.description());
                d.add("parameters", t.parametersSchema());
                declarations.add(d);
            }
            JsonObject tool = new JsonObject();
            tool.add("functionDeclarations", declarations);
            JsonArray tools = new JsonArray();
            tools.add(tool);
            body.add("tools", tools);
        }

        JsonObject config = new JsonObject();
        config.addProperty("maxOutputTokens", r.maxTokens());
        if (r.temperature() != null) {
            config.addProperty("temperature", r.temperature());
        }
        body.add("generationConfig", config);

        JsonArray contents = new JsonArray();
        JsonObject pendingUser = null;
        for (Message m : r.messages()) {
            if (m.role() == Role.ASSISTANT) {
                if (pendingUser != null) {
                    contents.add(pendingUser);
                    pendingUser = null;
                }
                JsonArray parts = new JsonArray();
                if (!m.text().isEmpty()) {
                    parts.add(textPart(m.text()));
                }
                for (ToolCall c : m.toolCalls()) {
                    JsonObject fc = new JsonObject();
                    fc.addProperty("name", c.name());
                    fc.add("args", c.arguments() == null ? new JsonObject() : c.arguments());
                    JsonObject part = new JsonObject();
                    part.add("functionCall", fc);
                    if (c.signature() != null) {
                        part.addProperty("thoughtSignature", c.signature());
                    }
                    parts.add(part);
                }
                JsonObject model = new JsonObject();
                model.addProperty("role", "model");
                model.add("parts", parts);
                contents.add(model);
            } else {
                if (pendingUser == null) {
                    pendingUser = new JsonObject();
                    pendingUser.addProperty("role", "user");
                    pendingUser.add("parts", new JsonArray());
                }
                JsonArray parts = pendingUser.getAsJsonArray("parts");
                if (m.role() == Role.TOOL) {
                    JsonObject response = new JsonObject();
                    response.addProperty("content", m.text());
                    JsonObject fr = new JsonObject();
                    fr.addProperty("name", m.toolName());
                    fr.add("response", response);
                    JsonObject part = new JsonObject();
                    part.add("functionResponse", fr);
                    parts.add(part);
                } else {
                    parts.add(textPart(m.text()));
                }
            }
        }
        if (pendingUser != null) {
            contents.add(pendingUser);
        }
        body.add("contents", contents);
        return body;
    }

    private static JsonObject textPart(String text) {
        JsonObject p = new JsonObject();
        p.addProperty("text", text);
        return p;
    }

    public LlmResponse fromWire(JsonObject o) {
        JsonArray candidates = ProtocolSupport.arr(o, "candidates");
        JsonObject candidate =
                candidates.isEmpty() ? new JsonObject() : candidates.get(0).getAsJsonObject();
        JsonObject content = ProtocolSupport.obj(candidate, "content");
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        if (content != null) {
            int index = 0;
            for (JsonElement e : ProtocolSupport.arr(content, "parts")) {
                JsonObject part = e.getAsJsonObject();
                if (part.has("text")) {
                    text.append(ProtocolSupport.str(part, "text"));
                }
                JsonObject fc = ProtocolSupport.obj(part, "functionCall");
                if (fc != null) {
                    JsonObject args = ProtocolSupport.obj(fc, "args");
                    JsonObject safeArgs = args == null ? new JsonObject() : args;
                    String id = ProtocolSupport.str(fc, "id");
                    calls.add(new ToolCall(
                            id != null ? id : ProtocolSupport.callId(index),
                            ProtocolSupport.str(fc, "name"),
                            safeArgs,
                            safeArgs.toString(),
                            ProtocolSupport.str(part, "thoughtSignature")));
                    index++;
                }
            }
        }
        String finish = ProtocolSupport.str(candidate, "finishReason");
        StopReason stop;
        if ("MAX_TOKENS".equals(finish)) {
            stop = StopReason.MAX_TOKENS;
        } else if (!calls.isEmpty()) {
            stop = StopReason.TOOL_USE;
        } else if ("STOP".equals(finish)) {
            stop = StopReason.END;
        } else {
            stop = StopReason.OTHER;
        }
        JsonObject usage = ProtocolSupport.obj(o, "usageMetadata");
        return new LlmResponse(
                text.toString(),
                calls,
                stop,
                new Usage(
                        ProtocolSupport.intOr(usage, "promptTokenCount", 0),
                        ProtocolSupport.intOr(usage, "candidatesTokenCount", 0)));
    }
}
