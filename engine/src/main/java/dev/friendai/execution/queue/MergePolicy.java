package dev.friendai.execution.queue;

/** What one tool admits when the queue optimizer folds two count steps into one action. */
@FunctionalInterface
public interface MergePolicy {
    /** The count argument this tool can merge, or null when its steps must stay separate. */
    String countArgument(String tool);

    /**
     * The largest combined count the tool itself admits in a single activation. A policy that states no limit keeps
     * the historical cap of 64; production policies read the tool's own declared limit.
     */
    default int mergeLimit(String tool) {
        return 64;
    }
}
