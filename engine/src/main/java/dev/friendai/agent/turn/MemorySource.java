package dev.friendai.agent.turn;

import dev.friendai.execution.tool.Caller;

/** What the brain knows about the current caller's visible memory. Empty when memory is off. */
@FunctionalInterface
public interface MemorySource {
    /** The memory block without a turn context, e.g. for a prompt preview. */
    String describe(Caller caller);

    /**
     * The memory block for one turn. {@code playerText} is what the player said in this turn, so a retrieval-backed
     * source can select facts that match it; a source that ignores it keeps the old behaviour through this default.
     */
    default String describe(Caller caller, String playerText) {
        return describe(caller);
    }

    MemorySource NONE = caller -> "";
}
