package dev.friedai.llm.protocol;

import dev.friedai.llm.model.LlmRequest;
import dev.friedai.llm.model.LlmResponse;
import dev.friedai.llm.provider.Endpoint;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface LlmProtocol {
    String id();

    CompletableFuture<LlmResponse> complete(Endpoint endpoint, LlmRequest request);

    CompletableFuture<List<String>> listModels(Endpoint endpoint);

    default boolean supportsTools() {
        return true;
    }
}
