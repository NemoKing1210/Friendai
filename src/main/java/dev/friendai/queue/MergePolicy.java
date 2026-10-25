package dev.friendai.queue;

@FunctionalInterface
public interface MergePolicy {
    String countArgument(String tool);
}
