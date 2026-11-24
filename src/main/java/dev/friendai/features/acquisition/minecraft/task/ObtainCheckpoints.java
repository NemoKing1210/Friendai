package dev.friendai.features.acquisition.minecraft.task;

import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.acquisition.TradingContext;
import dev.friendai.features.acquisition.minecraft.ObtainSubtaskFactory;
import dev.friendai.features.acquisition.minecraft.tool.ObtainTools;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Checkpoint decoding for acquisitions, contributed beside their definitions. The saved phase decides what is safe:
 * a local or station step replans from the live bag, which is how the parent continues anyway; a nested search is
 * never restarted, and an unreadable payload is refused rather than guessed.
 */
public final class ObtainCheckpoints {
    private ObtainCheckpoints() {}

    /**
     * @param trading the caller's current trade authority, resolved at restore time so a reloaded order never spends
     *     under an expired scope
     */
    public static Optional<Task> obtain(
            TaskState state,
            TaskContext context,
            ObtainSubtaskFactory factory,
            BiFunction<TaskContext, Caller, TradingContext> trading) {
        Optional<ObtainTask.Saved> saved = boundary(state, context, ObtainTask.TYPE, ObtainTask.Saved::decode);
        if (saved.isEmpty() || saved.get().phase() == ObtainTask.Phase.NESTED_SEARCH) {
            // A search may not restart under a fresh budget.
            return Optional.empty();
        }
        Optional<Caller> caller = requester(context, saved.get().principal());
        if (caller.isEmpty()) {
            return Optional.empty();
        }
        ObtainTask.Saved boundary = saved.get();
        return Optional.of(new ObtainTask(
                boundary.item(),
                boundary.count(),
                boundary.useContainers(),
                boundary.explore(),
                boundary.maxSteps(),
                boundary.maxTicks(),
                boundary.exploreRadius(),
                boundary.exploreBlocks(),
                boundary.exploreTicks(),
                boundary.searchScope(),
                caller.get(),
                trading(context, caller.get(), trading),
                factory));
    }

    /** The same boundary for a batch: its targets and effective options rebuild the order from the live bag. */
    public static Optional<Task> batch(
            TaskState state,
            TaskContext context,
            ObtainSubtaskFactory factory,
            BiFunction<TaskContext, Caller, TradingContext> trading) {
        Optional<BatchObtainTask.Saved> saved =
                boundary(state, context, BatchObtainTask.TYPE, BatchObtainTask.Saved::decode);
        if (saved.isEmpty() || saved.get().phase() == BatchObtainTask.Phase.NESTED_SEARCH) {
            return Optional.empty();
        }
        Optional<Caller> caller = requester(context, saved.get().principal());
        if (caller.isEmpty()) {
            return Optional.empty();
        }
        BatchObtainTask.Saved boundary = saved.get();
        return Optional.of(new BatchObtainTask(
                boundary.targets(),
                boundary.useContainers(),
                boundary.explore(),
                boundary.maxChildren(),
                boundary.maxTicks(),
                boundary.exploreRadius(),
                boundary.exploreBlocks(),
                boundary.exploreTicks(),
                boundary.searchScope(),
                caller.get(),
                trading(context, caller.get(), trading),
                factory));
    }

    private static <T> Optional<T> boundary(
            TaskState state, TaskContext context, String type, Function<String, Optional<T>> decode) {
        if (state == null || context == null || !type.equals(state.type()) || context.companion() == null) {
            return Optional.empty();
        }
        return decode.apply(state.data());
    }

    private static Optional<Caller> requester(TaskContext context, UUID principal) {
        if (principal == null) {
            return Optional.empty();
        }
        Caller caller = context.currentCaller(principal);
        if (caller == null || caller.access().ordinal() < AccessLevel.TRUSTED.ordinal()) {
            return Optional.empty();
        }
        return Optional.of(caller);
    }

    private static TradingContext trading(
            TaskContext context, Caller caller, BiFunction<TaskContext, Caller, TradingContext> trading) {
        return trading == null ? null : trading.apply(context, caller);
    }

    /** The production trading lookup: whatever the caller's scope permits right now. */
    public static TradingContext currentTrading(TaskContext context, Caller caller) {
        return ObtainTools.trading(context.companion(), caller);
    }
}
