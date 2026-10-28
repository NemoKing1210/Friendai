package dev.friendai.skill;

import dev.friendai.execution.tool.*;
import dev.friendai.foundation.teaching.TeachingContext;

/** Authorization is rechecked even if a model fabricates a hidden tool call. */
public final class TeachingPolicy {
    private TeachingPolicy() {}

    public static boolean authorize(TeachingContext context, Caller caller, long currentGeneration) {
        return context != null
                && caller != null
                && caller.access() == AccessLevel.OWNER
                && context.owner().equals(caller.uuid())
                && context.generation() == currentGeneration;
    }
}
