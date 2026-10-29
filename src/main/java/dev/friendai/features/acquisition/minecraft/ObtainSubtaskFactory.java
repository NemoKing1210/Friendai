package dev.friendai.features.acquisition.minecraft;

import dev.friendai.execution.tool.Caller;
import dev.friendai.features.acquisition.ObtainStep;
import dev.friendai.features.acquisition.TradingContext;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import java.util.Objects;
import java.util.function.Supplier;

/** Composition boundary for world tasks that can perform an acquisition plan step. */
@FunctionalInterface
public interface ObtainSubtaskFactory {
    ObtainSubtaskFactory UNAVAILABLE = (context, step, options) -> {
        throw new IllegalStateException("no acquisition subtask factory is configured for "
                + step.getClass().getSimpleName());
    };

    Created create(TaskContext context, ObtainStep step, Options options);

    record Options(
            String searchScope,
            Caller caller,
            TradingContext tradingContext,
            int exploreRadius,
            int exploreBlocks,
            int exploreTicks) {}

    record Created(Task task, Supplier<ResourceCandidates.Verification> visitVerification) {
        public Created {
            Objects.requireNonNull(task, "task");
        }

        public static Created task(Task task) {
            return new Created(task, null);
        }

        public static Created visitResource(Task task, Supplier<ResourceCandidates.Verification> visitVerification) {
            return new Created(task, Objects.requireNonNull(visitVerification));
        }
    }
}
