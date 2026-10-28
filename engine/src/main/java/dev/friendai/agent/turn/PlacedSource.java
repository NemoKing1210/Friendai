package dev.friendai.agent.turn;

import dev.friendai.execution.tool.Caller;

/** What the brain may show of the blocks this caller's companion placed. Empty when the ledger is off. */
@FunctionalInterface
public interface PlacedSource {
    PlacedSource NONE = caller -> "";

    /** The rendered placed-blocks block, or {@code ""} when the caller may not see any. */
    String describe(Caller caller);
}
