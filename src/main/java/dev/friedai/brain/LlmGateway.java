package dev.friedai.brain;

import dev.friedai.llm.model.LlmResponse;
import dev.friedai.llm.model.Message;
import dev.friedai.llm.model.ToolSpec;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** What the brain needs from an LLM: one completion for a system prompt, history and tools. */
public interface LlmGateway {
    CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools);
}
