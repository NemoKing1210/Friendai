package dev.friendai.task;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.Caller;

/**
 * Composition boundary for the one bounded search a world task may delegate to when its own scan found no target.
 * A feature task calls it; the server composes the acquisition search behind it, so a feature such as mining never
 * depends on the acquisition package and the feature graph stays acyclic.
 *
 * <p>The caller owns the loop: it starts the returned task, ticks it, and stops it once {@code maxTicks} ticks have
 * passed. {@code radius} and {@code maxTicks} are the caller's bounds, not a promise: an implementation may run a
 * narrower search with its own configured radius and budget. {@code caller} is the principal that asked for the work,
 * so the delegated search keeps exactly that authority and never invents a higher one.
 */
@FunctionalInterface
public interface BlockSearchTaskFactory {
    /** A bounded search for a block id or tag for {@code caller}; null when no search is available. */
    Task create(CompanionEntity companion, Caller caller, String blockOrTag, int radius, int maxTicks);

    BlockSearchTaskFactory NONE = (companion, caller, blockOrTag, radius, maxTicks) -> null;
}
