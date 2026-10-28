package dev.friendai.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.friendai.FriendaiMod;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Where each owner's companion was last seen. Persisted with the world so the mod can load the companion's chunk on
 * the next server start, even when no player is near it.
 */
public final class CompanionAnchors extends SavedData {
    /** One companion's dimension and chunk. */
    public record Anchor(ResourceKey<Level> dimension, ChunkPos chunk) {
        static final Codec<Anchor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        ResourceKey.codec(Registries.DIMENSION)
                                .fieldOf("dimension")
                                .forGetter(Anchor::dimension),
                        ChunkPos.CODEC.fieldOf("chunk").forGetter(Anchor::chunk))
                .apply(instance, Anchor::new));
    }

    private static final Codec<CompanionAnchors> CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Anchor.CODEC).xmap(CompanionAnchors::new, CompanionAnchors::all);

    public static final SavedDataType<CompanionAnchors> TYPE =
            new SavedDataType<>(FriendaiMod.id("companion_anchors"), CompanionAnchors::new, CODEC, DataFixTypes.LEVEL);

    private final Map<UUID, Anchor> anchors = new HashMap<>();

    public CompanionAnchors() {}

    private CompanionAnchors(Map<UUID, Anchor> anchors) {
        this.anchors.putAll(anchors);
    }

    public Optional<Anchor> get(UUID owner) {
        return Optional.ofNullable(anchors.get(owner));
    }

    /** Stores the owner's anchor; only marks the data dirty when it actually changed. */
    public void put(UUID owner, Anchor anchor) {
        Anchor previous = anchors.put(owner, anchor);
        if (!anchor.equals(previous)) {
            setDirty();
        }
    }

    public void remove(UUID owner) {
        if (anchors.remove(owner) != null) {
            setDirty();
        }
    }

    /** Every anchor, as a copy safe to iterate while removing entries. */
    public Map<UUID, Anchor> all() {
        return new HashMap<>(anchors);
    }
}
