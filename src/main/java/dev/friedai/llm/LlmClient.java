package dev.friedai.llm;

import com.google.gson.JsonObject;
import dev.friedai.brain.LlmGateway;
import dev.friedai.llm.model.LlmRequest;
import dev.friedai.llm.model.LlmResponse;
import dev.friedai.llm.model.Message;
import dev.friedai.llm.model.ToolSpec;
import dev.friedai.llm.provider.LlmProfile;
import dev.friedai.llm.provider.ProviderRegistry;
import dev.friedai.llm.provider.Route;
import dev.friedai.text.UserMessage;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** The configured LLM: resolves the route for the profile and sends requests through it. */
public final class LlmClient implements LlmGateway {
    private final ProviderRegistry registry;
    private final LlmProfile profile;

    public LlmClient(ProviderRegistry registry, LlmProfile profile) {
        this.registry = registry;
        this.profile = profile;
    }

    @Override
    public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
        try {
            Route route = registry.resolve(profile);
            LlmRequest request = new LlmRequest(
                    profile.model(), system, messages, tools, profile.temperature(), profile.maxTokens());
            return route.protocol().complete(route.endpoint(), request);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<List<String>> listModels() {
        try {
            Route route = registry.resolveModels(profile);
            return route.protocol().listModels(route.endpoint());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Sends one request with a single tool and reports whether the model called it. */
    public CompletableFuture<UserMessage> testConnection() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        ToolSpec ping = new ToolSpec("ping", "Connectivity check. Call this tool.", schema);
        return complete("You are a connectivity test. Call the ping tool.", List.of(Message.user("ping")), List.of(ping))
                .thenApply(r -> r.toolCalls().isEmpty()
                        ? UserMessage.of("friedai.config.test.ok_text")
                        : UserMessage.of("friedai.config.test.ok_tools"));
    }
}
