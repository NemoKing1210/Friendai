package dev.friendai.features.survival;

/**
 * Where and how a companion died, kept per owner so the next body can remember it.
 *
 * <p>Pure data: the damage source and the level are read by the Minecraft adapter, this record only
 * stores the facts and renders the one model-facing sentence.
 */
public record DeathRecord(String dimension, int x, int y, int z, String cause, String killer, long tick) {
    public DeathRecord {
        dimension = dimension == null ? "" : dimension;
        cause = cause == null ? "" : cause;
        killer = killer == null ? "" : killer;
    }

    /** One short sentence for the prompt; no coordinates beyond the fact, no raw ids. */
    public String describe() {
        String where = dimension.isBlank() ? "" : " in " + dimension;
        String how = killer.isBlank() ? (cause.isBlank() ? "" : ", died to " + cause) : ", killed by " + killer;
        return x + " " + y + " " + z + where + how;
    }
}
