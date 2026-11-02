package dev.friendai.agent.llm.stream;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.turn.LlmGateway;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A gateway that can deliver increments while the response is still arriving. The returned {@link LlmResponse} is the
 * complete, authoritative response; the increments are advisory and must never execute a tool by themselves.
 */
public interface StreamingLlmGateway extends LlmGateway {
    CompletableFuture<LlmResponse> completeStreaming(
            String system, List<Message> messages, List<ToolSpec> tools, Consumer<StreamDelta> onDelta);
}
