package dev.friendai.features.acquisition.minecraft;

import dev.friendai.execution.tool.Caller;
import dev.friendai.features.knowledge.search.TargetKind;
import dev.friendai.task.Task;

/** Composition boundary for search tasks requested by the knowledge tools. */
@FunctionalInterface
public interface ResourceSearchTaskFactory {
    Task create(
            TargetKind fixedTarget,
            String resource,
            String requestedStrategy,
            String scope,
            String heading,
            Caller caller);
}
