package dev.friendai.placed;

import dev.friendai.pathing.Pos;
import java.util.Objects;

/**
 * One block the companion placed: where, in which dimension, which block, when and by what. The dimension and
 * position identify the entry; the block id is what must still be there for the entry to count as present.
 */
public record PlacedBlock(String dimension, Pos pos, String blockId, long tick, String source) {

    public PlacedBlock {
        dimension = requireText(dimension, "dimension");
        pos = Objects.requireNonNull(pos, "pos");
        blockId = requireText(blockId, "block");
        if (tick < 0) {
            throw new IllegalArgumentException("tick must not be negative");
        }
        source = source == null || source.isBlank() ? "direct" : source.strip();
    }

    /** The cell this entry describes, as a mutable-free key. */
    public Pos position() {
        return pos;
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a placed block needs a " + what);
        }
        return value.strip();
    }
}
