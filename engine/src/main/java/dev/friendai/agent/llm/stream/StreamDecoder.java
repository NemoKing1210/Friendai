package dev.friendai.agent.llm.stream;

import java.util.List;

/** Turns transport events into provider-neutral increments; unknown events are ignored. */
public interface StreamDecoder {
    List<StreamDelta> accept(StreamEvent event);

    /** End of stream: flush any provider terminator that needs a final event. */
    default List<StreamDelta> finish() {
        return List.of();
    }
}
