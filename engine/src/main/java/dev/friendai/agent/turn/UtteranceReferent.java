package dev.friendai.agent.turn;

import java.util.Objects;
import java.util.UUID;

/**
 * What the player pointed at when their line was accepted, captured on the server thread. It is English-facing
 * evidence for one turn: a contextual order ("this block", "that one") means this target, not the player's next gaze.
 *
 * @param speaker the player who spoke
 * @param dimension the dimension id the aim was taken in, such as {@code minecraft:the_nether}
 * @param target the pointed block or entity id, or empty for {@link Kind#NONE}
 * @param gameTime the time of the aim, in the speaker's level
 */
public record UtteranceReferent(
        UUID speaker, String dimension, int x, int y, int z, String target, Kind kind, long gameTime) {
    public enum Kind {
        BLOCK,
        ENTITY,
        /** The speaker pointed at nothing within the look range. */
        NONE
    }

    public UtteranceReferent {
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(dimension, "dimension");
        kind = kind == null ? Kind.NONE : kind;
        target = target == null ? "" : target;
        if (dimension.isBlank()) throw new IllegalArgumentException("a referent needs a dimension");
        if (kind != Kind.NONE && target.isBlank()) throw new IllegalArgumentException("a target needs an id");
    }

    public boolean hasTarget() {
        return kind != Kind.NONE;
    }
}
