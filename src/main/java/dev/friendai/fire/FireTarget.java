package dev.friendai.fire;

import dev.friendai.pathing.Pos;
import java.util.List;

/**
 * What has to stop burning. The pure layer only names the target; the Minecraft adapter resolves it into
 * {@link BurningCell}s and a {@link FireFacts} snapshot.
 */
public sealed interface FireTarget {

    /** The companion itself. */
    record Self() implements FireTarget {}

    /** The player who owns the companion. */
    record Owner() implements FireTarget {}

    /** A burning mob or animal, identified by where it stands. */
    record BurningEntity(Pos pos, String kind) implements FireTarget {}

    /** One burning block: a fire, a soul fire or a lit campfire. */
    record Block(Pos pos) implements FireTarget {}

    /** A fire that spread over several blocks, in the order the adapter found them. */
    record Area(List<Pos> cells) implements FireTarget {
        public Area {
            cells = List.copyOf(cells);
        }
    }
}
