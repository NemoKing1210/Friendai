package dev.friendai.execution.queue;

@FunctionalInterface
public interface MergePolicy {
    String countArgument(String tool);
}
