package dev.friendai.task;

import dev.friendai.entity.CompanionEntity;

/**
 * Composition boundary for the one bounded search a world task may delegate to when its own scan found no target.
 * A feature task calls it; the server composes the acquisition search behind it, so a feature such as mining never
 * depends on the acquisition package and the feature graph stays acyclic.
 *
 * <p>The caller owns the loop: it starts the returned task, ticks it, and stops it once {@code maxTicks} ticks have
 * passed. The search's own configured radius and budget may still be narrower than {@code radius} and
 * {@code maxTicks}.
 */
@FunctionalInterface
public interface BlockSearchTaskFactory {
    /** A bounded search for a block id or tag; null when no search is available. */
    Task create(CompanionEntity companion, String blockOrTag, int radius, int maxTicks);

    BlockSearchTaskFactory NONE = (companion, blockOrTag, radius, maxTicks) -> null;
}
