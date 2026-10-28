package dev.friendai.survival;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Where and how a companion died, kept per owner so the next body can remember it.
 *
 * <p>Pure data: the damage source and the level are read by the Minecraft adapter, this record only
 * stores the facts and renders the one model-facing sentence.
 */
public record DeathRecord(String dimension, int x, int y, int z, String cause, String killer, long tick) {
    public static final Codec<DeathRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.optionalFieldOf("dimension", "").forGetter(DeathRecord::dimension),
                    Codec.INT.optionalFieldOf("x", 0).forGetter(DeathRecord::x),
                    Codec.INT.optionalFieldOf("y", 0).forGetter(DeathRecord::y),
                    Codec.INT.optionalFieldOf("z", 0).forGetter(DeathRecord::z),
                    Codec.STRING.optionalFieldOf("cause", "").forGetter(DeathRecord::cause),
                    Codec.STRING.optionalFieldOf("killer", "").forGetter(DeathRecord::killer),
                    Codec.LONG.optionalFieldOf("tick", 0L).forGetter(DeathRecord::tick))
            .apply(instance, DeathRecord::new));

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
