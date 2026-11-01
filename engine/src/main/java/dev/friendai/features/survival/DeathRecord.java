package dev.friendai.features.survival;

import java.util.List;
import java.util.UUID;

/**
 * Where and how a companion died, kept per owner so the next body can remember it and go back for what it dropped.
 *
 * <p>Pure data: the damage source and the level are read by the Minecraft adapter, this record only stores the facts
 * and renders the one model-facing sentence. The drops list is the recovery contract — the item entities the death
 * spawned, a stack list for anything that could not be attributed, and whether the spot can be walked to at all.
 */
public record DeathRecord(
        String dimension, int x, int y, int z, String cause, String killer, long tick, Drops drops, Respawn respawn) {

    /** The old seven-fact record: no drops known, no respawn recorded. */
    public DeathRecord(String dimension, int x, int y, int z, String cause, String killer, long tick) {
        this(dimension, x, y, z, cause, killer, tick, Drops.NONE, null);
    }

    public DeathRecord {
        dimension = dimension == null ? "" : dimension;
        cause = cause == null ? "" : cause;
        killer = killer == null ? "" : killer;
        drops = drops == null ? Drops.NONE : drops;
    }

    /** What the death scattered, and whether it can still be collected. */
    public record Drops(List<UUID> entities, List<String> stacks, boolean unrecoverable) {
        public static final Drops NONE = new Drops(List.of(), List.of(), false);

        public Drops {
            entities = entities == null ? List.of() : List.copyOf(entities);
            stacks = stacks == null ? List.of() : List.copyOf(stacks);
        }

        /** Nothing was carried, so there is nothing to go back for. */
        public boolean empty() {
            return entities.isEmpty() && stacks.isEmpty();
        }

        /** A drop list the recovery may walk to: something was dropped and the spot is not a hazard. */
        public boolean recoverable() {
            return !unrecoverable && !empty();
        }
    }

    /** Where the replacement body appeared, so a report can say how far the trip is. */
    public record Respawn(String dimension, int x, int y, int z) {
        public Respawn {
            dimension = dimension == null ? "" : dimension;
        }
    }

    /** Whether this death left something worth a trip that the trip could actually reach. */
    public boolean recoverable() {
        return drops.recoverable();
    }

    /** One short sentence for the prompt; no coordinates beyond the fact, no raw ids. */
    public String describe() {
        String where = dimension.isBlank() ? "" : " in " + dimension;
        String how = killer.isBlank() ? (cause.isBlank() ? "" : ", died to " + cause) : ", killed by " + killer;
        int stacks = Math.max(drops.stacks().size(), drops.entities().size());
        String lost = stacks == 0 ? "" : ", dropped " + stacks + " stacks";
        return x + " " + y + " " + z + where + how + lost;
    }
}
