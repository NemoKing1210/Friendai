package dev.friendai.brain;

import dev.friendai.tool.Caller;

/** What the brain may show of the caller's known places in the prompt. Empty when places are off. */
@FunctionalInterface
public interface PlaceSource {
    PlaceSource NONE = caller -> "";

    /** The rendered places block, or {@code ""} when the caller may not see any. */
    String describe(Caller caller);
}
