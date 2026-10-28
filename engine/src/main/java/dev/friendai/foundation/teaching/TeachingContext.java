package dev.friendai.foundation.teaching;

import java.util.UUID;

/** Capability issued only for one terminal outcome turn of the current brain generation. */
public record TeachingContext(UUID owner, String outcomeId, long generation, OutcomeKind kind) {
    public enum OutcomeKind {
        GOAL_SUCCESS,
        GOAL_FAILURE,
        SHIFT_SUCCESS,
        SHIFT_FAILURE
    }

    public TeachingContext {
        if (owner == null || outcomeId == null || outcomeId.length() > 120 || outcomeId.isBlank() || kind == null)
            throw new IllegalArgumentException("Invalid teaching context.");
    }
}
