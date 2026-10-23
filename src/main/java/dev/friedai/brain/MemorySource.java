package dev.friedai.brain;

import dev.friedai.tool.Caller;

/** What the brain knows about the current caller's visible memory. Empty when memory is off. */
@FunctionalInterface
public interface MemorySource {
    String describe(Caller caller);

    MemorySource NONE = caller -> "";
}
