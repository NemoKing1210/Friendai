package dev.friendai.agent.llm.protocol;

import dev.friendai.agent.llm.model.LlmRequest;
import dev.friendai.agent.llm.stream.SseFrames;
import dev.friendai.agent.llm.stream.StreamAssembly;

/**
 * A protocol that can answer incrementally. The ordinary request path stays the authority: this only adds the streamed
 * body and the frame decoding, and every protocol that does not implement it falls back to one ordinary request.
 */
public interface StreamingProtocol {
    /** Whether this protocol streams for the current build; a provider that cannot is never asked to. */
    default boolean supportsStreaming() {
        return true;
    }

    /** The endpoint path the streamed request goes to, appended to the profile's base URL. */
    String streamingPath();

    /** The same request as {@code toWire} with the provider's stream flag, as JSON. */
    String streamingBody(LlmRequest request);

    /** Feeds one decoded frame; text goes to the assembly, a tool call only accumulates there. */
    void onFrame(SseFrames.Frame frame, StreamAssembly assembly);
}
