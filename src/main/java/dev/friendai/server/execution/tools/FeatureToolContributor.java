package dev.friendai.server.execution.tools;

/** Registers the tools and task definitions owned by one gameplay feature. */
@FunctionalInterface
public interface FeatureToolContributor {
    void register(FeatureToolRegistry registry);
}
